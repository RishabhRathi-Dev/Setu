package io.setu.server.tcp;

import io.setu.core.protocol.BufferBudget;
import io.setu.core.protocol.ResponseBuffers;
import io.setu.server.dispatcher.RequestHandler;
import io.setu.server.dispatcher.RequestProcessor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One event loop; a connection admits exactly one frame and one reply at a time. */
public final class SelectorTcpServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SelectorTcpServer.class);
    private static final java.util.concurrent.atomic.AtomicLong SESSION_IDS = new java.util.concurrent.atomic.AtomicLong();
    private final InetSocketAddress address;
    private final int maxFrameSize;
    private final TcpServerOptions options;
    private final RequestProcessor processor;
    private final BufferBudget requests;
    private final BufferBudget responses;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.LongAdder acceptedConnections = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder rejectedConnections = new java.util.concurrent.atomic.LongAdder();
    private final ConcurrentLinkedQueue<Runnable> completions = new ConcurrentLinkedQueue<>();
    private Selector selector;
    private ServerSocketChannel listener;
    private Thread eventThread;
    private ExecutorService workers;

    public SelectorTcpServer(InetSocketAddress address, int maxFrameSize, TcpServerOptions options, RequestProcessor processor) {
        this.address = Objects.requireNonNull(address);
        if (maxFrameSize < 0 || maxFrameSize > Integer.MAX_VALUE - 4) throw new IllegalArgumentException("invalid frame limit");
        this.maxFrameSize = maxFrameSize;
        this.options = Objects.requireNonNull(options);
        this.processor = Objects.requireNonNull(processor);
        requests = new BufferBudget(options.requestBufferBytes());
        responses = new BufferBudget(options.responseBufferBytes());
    }
    public SelectorTcpServer(InetSocketAddress address, int maxFrameSize, TcpServerOptions options, RequestHandler handler) {
        this(address, maxFrameSize, options, RequestProcessor.plaintext(handler));
    }
    public synchronized void start() throws IOException {
        if (running.get()) throw new IllegalStateException("Server is already running");
        selector = Selector.open();
        listener = ServerSocketChannel.open();
        try {
            listener.configureBlocking(false);
            listener.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            listener.bind(address, options.acceptBacklog());
            listener.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException | RuntimeException e) { listener.close(); selector.close(); throw e; }
        workers = Executors.newVirtualThreadPerTaskExecutor();
        running.set(true);
        eventThread = Thread.ofPlatform().name("setu-selector-" + localAddress().getPort()).start(this::runLoop);
        LOG.info("Selector server started address={} max_connections={} request_budget={} response_budget={}",
                localAddress(), options.maxConnections(), requests.maximumBytes(), responses.maximumBytes());
    }
    public boolean isRunning() { return running.get(); }
    public long inFlightRequestBytes() { return requests.usedBytes(); }
    public long inFlightResponseBytes() { return responses.usedBytes(); }
    public java.util.Map<String, Long> operationalGauges() {
        return java.util.Map.of("connections_active", (long) clients.size(),
                "connections_accepted_total", acceptedConnections.sum(),
                "connections_limit_rejections_total", rejectedConnections.sum(),
                "connections_max", (long) options.maxConnections(),
                "request_buffer_used_bytes", requests.usedBytes(), "request_buffer_max_bytes", requests.maximumBytes(),
                "response_buffer_used_bytes", responses.usedBytes(), "response_buffer_max_bytes", responses.maximumBytes());
    }
    public InetSocketAddress localAddress() {
        if (listener == null || !listener.isOpen()) throw new IllegalStateException("Server has not been started");
        try { return (InetSocketAddress) listener.getLocalAddress(); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }
    private void runLoop() {
        try {
            while (running.get()) {
                drain();
                selector.select(100);
                drain();
                var iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next(); iterator.remove();
                    if (!key.isValid()) continue;
                    try {
                        if (key.isAcceptable()) accept();
                        else {
                            Client client = (Client) key.attachment();
                            if (key.isReadable()) read(key, client);
                            if (key.isValid() && key.isWritable()) write(key, client);
                        }
                    } catch (IOException | RuntimeException e) {
                        if (!(key.attachment() instanceof Client client)) throw e;
                        if (e instanceof BufferBudget.Exhausted) LOG.debug("Selector frame admission rejected session={}", client.sessionId);
                        else LOG.warn("Selector connection failed session={}", client.sessionId, e);
                        closeClient(client);
                    }
                }
                if (options.socketTimeoutMillis() > 0) {
                    long now = System.nanoTime();
                    for (Client client : clients)
                        if (!client.inFlight && now - client.activity > options.socketTimeoutMillis() * 1_000_000L)
                            closeClient(client);
                }
                long now = System.nanoTime();
                for (Client client : clients)
                    if ((client.outbound != null && now - client.writeStarted > options.writeTimeoutMillis() * 1_000_000L)
                            || (client.receiving && now - client.frameStarted > options.frameTimeoutMillis() * 1_000_000L))
                        closeClient(client);
            }
        } catch (IOException | RuntimeException e) { if (running.get()) LOG.error("Selector loop failed", e); }
        finally { running.set(false); for (Client client : clients) closeClient(client); }
    }
    private void accept() throws IOException {
        SocketChannel channel;
        while ((channel = listener.accept()) != null) {
            acceptedConnections.increment();
            if (clients.size() >= options.maxConnections()) { rejectedConnections.increment(); channel.close(); continue; }
            BufferBudget.Lease connectionCredit = null;
            try {
                connectionCredit = requests.reserve(1024);
                channel.configureBlocking(false);
                channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                Client client = new Client(channel, connectionCredit);
                client.key = channel.register(selector, SelectionKey.OP_READ, client);
                clients.add(client);
                LOG.debug("Selector session opened session={} peer={} active_connections={}", client.sessionId, channel.getRemoteAddress(), clients.size());
            } catch (BufferBudget.Exhausted e) { channel.close(); }
            catch (IOException | RuntimeException e) {
                if (connectionCredit != null) connectionCredit.close();
                channel.close(); throw e;
            }
        }
    }
    private void read(SelectionKey key, Client client) throws IOException {
        if (client.body == null) {
            int count = client.channel.read(client.header);
            if (count < 0) { closeClient(client); return; }
            if (count > 0) {
                client.activity = System.nanoTime();
                if (!client.receiving) {
                    client.receiving = true;
                    client.frameStarted = client.activity;
                }
            }
            if (client.header.hasRemaining()) return;
            int length = client.header.flip().getInt();
            client.header.clear();
            if (length < 0 || length > maxFrameSize) throw new IOException("Invalid frame length");
            client.requestCredit = requests.reserve(2L * length + 1024);
            client.body = new io.setu.storage.log.MessageValue.Frame.Reader(length);
        }
        int count = client.body.readFrom(client.channel);
        if (count < 0) { closeClient(client); return; }
        if (count > 0) client.activity = System.nanoTime();
        if (!client.body.isComplete()) return;
        if (System.nanoTime() - client.frameStarted >= options.frameTimeoutMillis() * 1_000_000L) {
            closeClient(client);
            return;
        }
        var request = client.body.finish();
        client.receiving = false;
        client.body = null;
        client.inFlight = true;
        key.interestOps(0);
        BufferBudget.Lease requestCredit = client.requestCredit;
        client.requestCredit = null;
        try { workers.execute(() -> dispatch(client, request, requestCredit)); }
        catch (RuntimeException e) { requestCredit.close(); throw e; }
    }
    private void dispatch(Client client, io.setu.storage.log.MessageValue.Frame request, BufferBudget.Lease credit) {
        ResponseBuffers responseCredit = new ResponseBuffers(responses);
        boolean transferred = false;
        try (credit; var requestContext = io.setu.core.protocol.RequestBuffers.activate(credit);
             var sessionContext = org.slf4j.MDC.putCloseable("sessionId", Long.toString(client.sessionId));
             var context = responseCredit.activate()) {
            byte[] response = Objects.requireNonNull(processor.process(null, request));
            if (response.length > maxFrameSize) throw new IOException("Response exceeds frame limit");
            responseCredit.ensure(response.length);
            LOG.debug("Selector frame exchanged session={} request_bytes={} response_bytes={}", client.sessionId, request.length(), response.length);
            // The four-byte header is separate; no second framed copy of the entire response.
            completions.add(() -> {
                if (!running.get() || !client.key.isValid()) { responseCredit.close(); return; }
                client.responseCredit = responseCredit;
                client.outbound = new ByteBuffer[]{ByteBuffer.allocate(4).putInt(response.length).flip(), ByteBuffer.wrap(response)};
                client.activity = System.nanoTime();
                client.writeStarted = client.activity;
                client.key.interestOps(SelectionKey.OP_WRITE);
            });
            transferred = true;
        } catch (Exception e) {
            LOG.warn("Selector dispatch failed session={}", client.sessionId, e);
            completions.add(() -> closeClient(client));
        }
        finally {
            if (!transferred) responseCredit.close();
            // close() drains completions after all dispatch workers have terminated.
            selector.wakeup();
        }
    }
    private void write(SelectionKey key, Client client) throws IOException {
        if (client.channel.write(client.outbound) > 0) client.activity = System.nanoTime();
        if (!client.outbound[0].hasRemaining() && !client.outbound[1].hasRemaining()) {
            client.outbound = null;
            client.responseCredit.close(); client.responseCredit = null;
            client.inFlight = false;
            key.interestOps(SelectionKey.OP_READ);
        }
    }
    private void drain() { Runnable action; while ((action = completions.poll()) != null) action.run(); }
    private void closeClient(Client client) {
        if (client == null) return;
        client.key.cancel();
        try { client.channel.close(); } catch (IOException ignored) { }
        if (client.requestCredit != null) { client.requestCredit.close(); client.requestCredit = null; }
        if (client.responseCredit != null) { client.responseCredit.close(); client.responseCredit = null; }
        client.connectionCredit.close();
        client.body = null; client.outbound = null;
        if (clients.remove(client)) LOG.debug("Selector session closed session={} active_connections={}", client.sessionId, clients.size());
    }
    public synchronized void close() throws IOException {
        running.set(false);
        if (selector == null) return;
        selector.wakeup();
        if (listener != null) listener.close();
        // Closing sockets lets any asynchronous dispatch observe cancellation on its next write.
        for (Client client : clients) try { client.channel.close(); } catch (IOException ignored) { }
        boolean interrupted = false;
        if (eventThread != null && eventThread != Thread.currentThread()) {
            while (eventThread.isAlive()) try { eventThread.join(); } catch (InterruptedException e) { interrupted = true; }
        }
        if (workers != null) {
            workers.shutdownNow();
            while (!workers.isTerminated()) try { workers.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException e) { interrupted = true; }
        }
        drain();
        for (Client client : clients) closeClient(client);
        selector.close();
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static final class Client {
        final SocketChannel channel;
        final long sessionId = SESSION_IDS.incrementAndGet();
        final ByteBuffer header = ByteBuffer.allocate(4);
        final BufferBudget.Lease connectionCredit;
        SelectionKey key;
        io.setu.storage.log.MessageValue.Frame.Reader body;
        BufferBudget.Lease requestCredit;
        ResponseBuffers responseCredit;
        ByteBuffer[] outbound;
        boolean inFlight;
        long activity = System.nanoTime();
        long writeStarted;
        long frameStarted;
        boolean receiving;
        Client(SocketChannel channel, BufferBudget.Lease connectionCredit) { this.channel = channel; this.connectionCredit = connectionCredit; }
    }
}
