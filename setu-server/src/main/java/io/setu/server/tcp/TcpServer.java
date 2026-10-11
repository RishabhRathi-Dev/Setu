package io.setu.server.tcp;

import io.setu.server.connection.ConnectionHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
/** A small framed TCP server. Each accepted connection is served by a virtual thread. */
public final class TcpServer implements AutoCloseable {

    private final InetSocketAddress bindAddress;
    private final ConnectionHandler connectionHandler;
    private final TcpServerOptions options;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Set<SocketChannel> clients = ConcurrentHashMap.newKeySet();
    private volatile ExecutorService connectionExecutor;

    private volatile ServerSocketChannel serverChannel;
    private volatile Thread acceptThread;

    public TcpServer(InetSocketAddress bindAddress, ConnectionHandler connectionHandler) {
        this(bindAddress, connectionHandler, TcpServerOptions.defaults());
    }

    public TcpServer(InetSocketAddress bindAddress, ConnectionHandler connectionHandler, TcpServerOptions options) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.connectionHandler = Objects.requireNonNull(connectionHandler, "connectionHandler");
        this.options = Objects.requireNonNull(options, "options");
        if (connectionHandler instanceof io.setu.server.connection.FramedConnectionHandler framed)
            framed.configureBudgets(options.requestBufferBytes(), options.responseBufferBytes(), options.writeTimeoutMillis());
    }

    /** Binds synchronously, then returns once the accept loop has been started. */
    public synchronized void start() throws IOException {
        if (running.get()) {
            throw new IllegalStateException("Server is already running");
        }
        ServerSocketChannel channel = ServerSocketChannel.open();
        try {
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            channel.configureBlocking(true);
            channel.bind(bindAddress, options.acceptBacklog());
        } catch (IOException failure) {
            channel.close();
            throw failure;
        }

        serverChannel = channel;
        connectionExecutor = Executors.newVirtualThreadPerTaskExecutor();
        running.set(true);
        log.info("TCP server started address={} backlog={} max_connections={} socket_timeout_ms={}",
                channel.getLocalAddress(), options.acceptBacklog(), options.maxConnections(), options.socketTimeoutMillis());
        acceptThread = Thread.ofPlatform().name("setu-accept-" + ((InetSocketAddress) channel.getLocalAddress()).getPort())
                .start(this::acceptConnections);
    }

    public boolean isRunning() {
        return running.get();
    }

    /** The actual bound address, useful when binding to port zero. */
    public InetSocketAddress localAddress() {
        ServerSocketChannel channel = serverChannel;
        if (channel == null || !channel.isOpen()) {
            throw new IllegalStateException("Server has not been started");
        }
        try {
            return (InetSocketAddress) channel.getLocalAddress();
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read the server's local address", failure);
        }
    }

    private void acceptConnections() {
        try {
            while (running.get()) {
                SocketChannel client = serverChannel.accept();
                clients.add(client);
                if (clients.size() > options.maxConnections()) {
                    log.warn("Connection rejected: active_connections={} max_connections={}", clients.size(), options.maxConnections());
                    clients.remove(client);
                    closeQuietly(client);
                    continue;
                }
                try {
                    connectionExecutor.execute(() -> handleClient(client));
                } catch (RuntimeException rejected) {
                    clients.remove(client);
                    closeQuietly(client);
                    if (running.get()) {
                        throw rejected;
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            if (running.get()) {
                log.error("TCP accept loop failed", failure);
                closeAfterFailure();
            }
        }
    }

    private void handleClient(SocketChannel client) {
        try {
            client.setOption(StandardSocketOptions.TCP_NODELAY, true);
            if (options.socketTimeoutMillis() > 0) {
                client.socket().setSoTimeout(options.socketTimeoutMillis());
            }
            connectionHandler.handle(client);
        } catch (IOException | RuntimeException failure) {
            if (running.get()) {
                if (failure instanceof io.setu.core.protocol.BufferBudget.Exhausted)
                    log.debug("Closing client connection after frame admission rejection");
                else log.warn("Closing client connection after request failure", failure);
            }
        } finally {
            closeQuietly(client);
            clients.remove(client);
        }
    }

    private void closeAfterFailure() {
        try {
            close();
        } catch (IOException ignored) {
            // The accept loop is already failing; close is best-effort here.
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!running.getAndSet(false)) {
            ExecutorService executor = connectionExecutor;
            if (executor != null) {
                executor.shutdownNow();
            }
            return;
        }
        IOException failure = null;
        ServerSocketChannel channel = serverChannel;
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure = closeFailure;
            }
        }
        for (SocketChannel client : clients) {
            closeQuietly(client);
        }
        ExecutorService executor = connectionExecutor;
        if (executor != null) {
            executor.shutdownNow();
            try { executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        Thread thread = acceptThread;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (failure != null) {
            throw failure;
        }
        log.info("TCP server stopped address={} remaining_connections={}", bindAddress, clients.size());
    }

    private static void closeQuietly(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Best effort during connection cleanup.
        }
    }
}
