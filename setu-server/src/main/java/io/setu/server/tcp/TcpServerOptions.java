package io.setu.server.tcp;

/** Resource limits applied by the TCP acceptor. A zero socket timeout disables idle I/O timeout. */
public record TcpServerOptions(int maxConnections, int acceptBacklog, int socketTimeoutMillis) {
    public TcpServerOptions {
        if (maxConnections <= 0) throw new IllegalArgumentException("maxConnections must be positive");
        if (acceptBacklog <= 0) throw new IllegalArgumentException("acceptBacklog must be positive");
        if (socketTimeoutMillis < 0) throw new IllegalArgumentException("socketTimeoutMillis must be non-negative");
    }

    public static TcpServerOptions defaults() {
        return new TcpServerOptions(10_000, 256, 0);
    }
}
