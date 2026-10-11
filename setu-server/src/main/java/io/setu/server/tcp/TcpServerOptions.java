package io.setu.server.tcp;

/** Resource limits applied by the TCP acceptor. A zero socket timeout disables idle I/O timeout. */
public record TcpServerOptions(int maxConnections, int acceptBacklog, int socketTimeoutMillis,
                               long requestBufferBytes, long responseBufferBytes, int writeTimeoutMillis) {
    public TcpServerOptions(int connections, int backlog, int timeout) {
        this(connections, backlog, timeout, 128L * 1024 * 1024, 128L * 1024 * 1024);
    }
    public TcpServerOptions(int connections, int backlog, int timeout, long requests, long responses) {
        this(connections, backlog, timeout, requests, responses, 30_000);
    }
    public TcpServerOptions {
        if (maxConnections <= 0) throw new IllegalArgumentException("maxConnections must be positive");
        if (acceptBacklog <= 0) throw new IllegalArgumentException("acceptBacklog must be positive");
        if (socketTimeoutMillis < 0) throw new IllegalArgumentException("socketTimeoutMillis must be non-negative");
        if (requestBufferBytes <= 0 || responseBufferBytes <= 0)
            throw new IllegalArgumentException("buffer budgets must be positive");
        if (writeTimeoutMillis <= 0) throw new IllegalArgumentException("write timeout must be positive");
    }

    public static TcpServerOptions defaults() {
        return new TcpServerOptions(10_000, 256, 0);
    }
}
