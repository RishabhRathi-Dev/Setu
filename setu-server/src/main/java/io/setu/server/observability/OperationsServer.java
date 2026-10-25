package io.setu.server.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Optional read-only loopback endpoint. No remote administration or authentication surface. */
public final class OperationsServer implements AutoCloseable {
    private final HttpServer server;
    private final ThreadPoolExecutor workers;

    public OperationsServer(InetSocketAddress address, BrokerMetrics metrics, BooleanSupplier transportReady) throws IOException {
        this(address, metrics, transportReady, java.util.Map::of);
    }

    public OperationsServer(InetSocketAddress address, BrokerMetrics metrics, BooleanSupplier transportReady,
                            java.util.function.Supplier<java.util.Map<String, Long>> transportMetrics) throws IOException {
        if (address.getAddress() == null || !address.getAddress().isLoopbackAddress())
            throw new IllegalArgumentException("Operations endpoint must bind to a loopback address");
        server = HttpServer.create(address, 16);
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16),
                Thread.ofPlatform().daemon().name("setu-operations-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(workers);
        server.createContext("/metrics", exchange -> {
            if (reject(exchange, "/metrics")) return;
            respond(exchange, 200, metrics.scrape(transportReady.getAsBoolean(), transportMetrics.get()),
                    "text/plain; version=0.0.4; charset=utf-8");
        });
        server.createContext("/ready", exchange -> {
            if (reject(exchange, "/ready")) return;
            boolean ready = metrics.isReady() && transportReady.getAsBoolean();
            respond(exchange, ready ? 200 : 503, ready ? "ready\n" : "not ready\n", "text/plain; charset=utf-8");
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String value, String type) throws IOException {
        try (exchange) {
            byte[] body = value.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private static boolean reject(HttpExchange exchange, String path) throws IOException {
        int status = !exchange.getRequestURI().getPath().equals(path) ? 404
                : !exchange.getRequestMethod().equals("GET") ? 405 : 0;
        if (status == 0) return false;
        try (exchange) {
            if (status == 405) exchange.getResponseHeaders().set("Allow", "GET");
            exchange.sendResponseHeaders(status, -1);
        }
        return true;
    }
    public InetSocketAddress localAddress() { return server.getAddress(); }
    @Override public void close() { server.stop(0); workers.shutdownNow(); }
}
