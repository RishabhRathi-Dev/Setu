package io.setu.server.tcp;

import io.setu.core.protocol.FrameCodec;
import io.setu.server.dispatcher.RequestHandler;
import io.setu.server.dispatcher.RequestProcessor;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Plaintext selector transport with asynchronous request dispatch and bounded client count. */
public final class SelectorTcpServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SelectorTcpServer.class);
    private static final int INITIAL_READ_CAPACITY = 8 * 1024;

    private final InetSocketAddress bindAddress;
    private final int maxFrameSize;
    private final TcpServerOptions options;
    private final RequestProcessor processor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Set<SocketChannel> clients = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Runnable> completions = new ConcurrentLinkedQueue<>();
    private volatile Selector selector;
    private volatile ServerSocketChannel listener;
    private volatile Thread eventThread;
    private volatile ExecutorService requestExecutor;

    public SelectorTcpServer(InetSocketAddress bindAddress, int maxFrameSize,
                             TcpServerOptions options, RequestProcessor processor) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        if (maxFrameSize < 0 || maxFrameSize > Integer.MAX_VALUE - Integer.BYTES)
            throw new IllegalArgumentException("maxFrameSize is outside the supported range");
        this.maxFrameSize = maxFrameSize;
        this.options = Objects.requireNonNull(options, "options");
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    public SelectorTcpServer(InetSocketAddress bindAddress, int maxFrameSize,
                             TcpServerOptions options, RequestHandler handler) {
        this(bindAddress, maxFrameSize, options,
                (identity, request) -> Objects.requireNonNull(handler, "handler").handle(request));
    }

    public synchronized void start() throws IOException {
        if (running.get()) throw new IllegalStateException("Server is already running");
        Selector openedSelector = Selector.open();
        ServerSocketChannel openedListener = ServerSocketChannel.open();
        try {
            openedListener.configureBlocking(false);
            openedListener.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            openedListener.bind(bindAddress, options.acceptBacklog());
            openedListener.register(openedSelector, SelectionKey.OP_ACCEPT);
        } catch (IOException failure) {
            openedListener.close();
            openedSelector.close();
            throw failure;
        }
        selector = openedSelector;
        listener = openedListener;
        requestExecutor = Executors.newVirtualThreadPerTaskExecutor();
        running.set(true);
        eventThread = Thread.ofPlatform().name("setu-selector-" + localAddress().getPort()).start(this::runLoop);
    }

    public InetSocketAddress localAddress() {
        ServerSocketChannel current = listener;
        if (current == null || !current.isOpen()) throw new IllegalStateException("Server has not been started");
        try { return (InetSocketAddress) current.getLocalAddress(); }
        catch (IOException e) { throw new IllegalStateException("Unable to read bound address", e); }
    }

    public boolean isRunning() { return running.get(); }

    private void runLoop() {
        try {
            while (running.get()) {
                drainCompletions();
                selector.select(500);
                drainCompletions();
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();
                    if (!key.isValid()) continue;
                    try {
                        if (key.isAcceptable()) acceptClients();
                        else {
                            ClientState state = (ClientState) key.attachment();
                            if (key.isReadable()) readClient(key, state);
                            if (key.isValid() && key.isWritable()) writeClient(key, state);
                        }
                    } catch (IOException | RuntimeException failure) {
                        closeKey(key);
                    }
                }
                closeIdleClients();
            }
        } catch (IOException failure) {
            if (running.get()) LOG.error("Selector loop failed", failure);
        } finally {
            try { close(); } catch (IOException e) { LOG.debug("Error closing selector server", e); }
        }
    }

    private void acceptClients() throws IOException {
        SocketChannel client;
        while ((client = listener.accept()) != null) {
            if (clients.size() >= options.maxConnections()) {
                client.close();
                continue;
            }
            client.configureBlocking(false);
            client.setOption(StandardSocketOptions.TCP_NODELAY, true);
            ClientState state = new ClientState(client, maxFrameSize);
            client.register(selector, SelectionKey.OP_READ, state);
            clients.add(client);
        }
    }

    private void readClient(SelectionKey key, ClientState state) throws IOException {
        ensureReadCapacity(state);
        int count = state.channel.read(state.inbound);
        if (count < 0) { closeKey(key); return; }
        if (count == 0) return;
        state.lastActivityNanos = System.nanoTime();
        if (processBufferedRequest(key, state)) key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
    }

    private boolean processBufferedRequest(SelectionKey key, ClientState state) throws IOException {
        state.inbound.flip();
        if (state.expectedBodyLength < 0) {
            if (state.inbound.remaining() < Integer.BYTES) {
                state.inbound.compact();
                return false;
            }
            state.expectedBodyLength = state.inbound.getInt();
            if (state.expectedBodyLength < 0 || state.expectedBodyLength > maxFrameSize) {
                throw new IOException("Invalid frame length: " + state.expectedBodyLength);
            }
        }
        if (state.inbound.remaining() < state.expectedBodyLength) {
            state.inbound.compact();
            ensureReadCapacity(state);
            return false;
        }
        byte[] request = new byte[state.expectedBodyLength];
        state.inbound.get(request);
        state.expectedBodyLength = -1;
        state.inbound.compact();
        state.inFlight = true;
        requestExecutor.execute(() -> dispatch(state, key, request));
        return true;
    }

    private void dispatch(ClientState state, SelectionKey key, byte[] request) {
        try {
            byte[] response = Objects.requireNonNull(processor.process(null, request), "request processor response");
            ByteArrayOutputStream framed = new ByteArrayOutputStream();
            FrameCodec.writeFrame(new DataOutputStream(framed), response, maxFrameSize);
            ByteBuffer bytes = ByteBuffer.wrap(framed.toByteArray());
            completions.add(() -> {
                if (!key.isValid() || !state.channel.isOpen()) return;
                state.outbound = bytes;
                state.lastActivityNanos = System.nanoTime();
                key.interestOps(SelectionKey.OP_WRITE);
            });
        } catch (IOException | RuntimeException failure) {
            completions.add(() -> closeKey(key));
        }
        selector.wakeup();
    }

    private void writeClient(SelectionKey key, ClientState state) throws IOException {
        if (state.outbound == null) { key.interestOps(0); return; }
        state.channel.write(state.outbound);
        state.lastActivityNanos = System.nanoTime();
        if (!state.outbound.hasRemaining()) {
            state.outbound = null;
            state.inFlight = false;
            shrinkReadBuffer(state);
            if (processBufferedRequest(key, state)) key.interestOps(0);
            else key.interestOps(SelectionKey.OP_READ);
        }
    }

    private void ensureReadCapacity(ClientState state) throws IOException {
        int desired = state.expectedBodyLength < 0 ? state.inbound.position() + Integer.BYTES : state.expectedBodyLength;
        if (state.inbound.capacity() >= desired && state.inbound.hasRemaining()) return;
        int target = Math.min(maxFrameSize + Integer.BYTES,
                Math.max(desired, Math.max(INITIAL_READ_CAPACITY, state.inbound.capacity() * 2)));
        if (target <= state.inbound.capacity()) throw new IOException("Input buffer reached configured frame limit");
        ByteBuffer replacement = ByteBuffer.allocate(target);
        state.inbound.flip();
        replacement.put(state.inbound);
        state.inbound = replacement;
    }

    private void shrinkReadBuffer(ClientState state) {
        if (state.inbound.capacity() <= INITIAL_READ_CAPACITY * 2
                || state.inbound.position() > INITIAL_READ_CAPACITY) return;
        ByteBuffer replacement = ByteBuffer.allocate(INITIAL_READ_CAPACITY);
        state.inbound.flip();
        replacement.put(state.inbound);
        state.inbound = replacement;
    }

    private void closeIdleClients() {
        if (options.socketTimeoutMillis() <= 0) return;
        long timeout = options.socketTimeoutMillis() * 1_000_000L;
        long now = System.nanoTime();
        for (SelectionKey key : selector.keys()) {
            if (key.attachment() instanceof ClientState state && !state.inFlight
                    && now - state.lastActivityNanos > timeout)
                closeKey(key);
        }
    }

    private void drainCompletions() {
        Runnable completion;
        while ((completion = completions.poll()) != null) completion.run();
    }

    private void closeKey(SelectionKey key) {
        if (key == null) return;
        Object attachment = key.attachment();
        key.cancel();
        try { key.channel().close(); } catch (IOException ignored) { }
        if (attachment instanceof ClientState state) clients.remove(state.channel);
    }

    @Override
    public synchronized void close() throws IOException {
        if (!running.getAndSet(false)) return;
        Selector currentSelector = selector;
        if (currentSelector != null) currentSelector.wakeup();
        if (listener != null) listener.close();
        for (SocketChannel client : clients) try { client.close(); } catch (IOException ignored) { }
        clients.clear();
        completions.clear();
        ExecutorService executor = requestExecutor;
        if (executor != null) executor.shutdownNow();
        Thread thread = eventThread;
        if (thread != null && thread != Thread.currentThread()) {
            try { thread.join(5_000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (currentSelector != null) currentSelector.close();
    }

    private static final class ClientState {
        private final SocketChannel channel;
        private ByteBuffer inbound = ByteBuffer.allocate(INITIAL_READ_CAPACITY);
        private int expectedBodyLength = -1;
        private ByteBuffer outbound;
        private boolean inFlight;
        private long lastActivityNanos = System.nanoTime();

        private ClientState(SocketChannel channel, int maxFrameSize) { this.channel = channel; }
    }
}
