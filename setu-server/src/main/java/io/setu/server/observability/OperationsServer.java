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
        if (address.getAddress() == null || !address.getAddress().isLoopbackAddress())
            throw new IllegalArgumentException("Operations endpoint must bind to a loopback address");
        server = HttpServer.create(address, 16);
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16),
                Thread.ofPlatform().daemon().name("setu-operations-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(workers);
        server.createContext("/metrics", exchange -> respond(exchange, "/metrics", 200, metrics.scrape(), "text/plain; version=0.0.4; charset=utf-8"));
        server.createContext("/ready", exchange -> {
            boolean ready = metrics.isReady() && transportReady.getAsBoolean();
            respond(exchange, "/ready", ready ? 200 : 503, ready ? "ready\n" : "not ready\n", "text/plain; charset=utf-8");
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, String path, int status, String value, String type) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals(path)) { exchange.sendResponseHeaders(404, -1); return; }
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                exchange.sendResponseHeaders(405, -1); return;
            }
            byte[] body = value.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }
    public InetSocketAddress localAddress() { return server.getAddress(); }
    @Override public void close() { server.stop(0); workers.shutdownNow(); }
}
