package io.setu.server;

import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.*;
import io.setu.server.observability.BrokerMetrics;
import io.setu.server.observability.OperationsServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class OperationsServerTests {
    @Test void endpointReportsErrorsLatencyAndReadinessWithoutUnboundedLabels() throws Exception {
        try (var broker = new DefaultBroker(1024); var client = HttpClient.newHttpClient()) {
            var metrics = new BrokerMetrics(broker);
            var transport = new AtomicBoolean(true);
            try (var server = new OperationsServer(new InetSocketAddress("127.0.0.1", 0), metrics, transport::get)) {
                metrics.execute(new BrokerRequest(1, Command.CREATE_TOPIC, "private-topic", 0, 0, 0, null, 1, null, null));
                assertThrows(io.setu.core.broker.BrokerException.class, () -> metrics.execute(new BrokerRequest(1,
                        Command.FETCH, "missing", 0, 0, 1024, null, 0, null, null)));
                String base = "http://127.0.0.1:" + server.localAddress().getPort();
                var scrape = client.send(HttpRequest.newBuilder(URI.create(base + "/metrics")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, scrape.statusCode());
                assertTrue(scrape.body().contains("error=\"UNKNOWN_TOPIC\"} 1"));
                assertTrue(scrape.body().contains("setu_request_duration_seconds_count{command=\"FETCH\"} 1"));
                assertFalse(scrape.body().contains("private-topic"));
                assertEquals(200, get(client, base + "/ready"));
                transport.set(false);
                assertEquals(503, get(client, base + "/ready"));
                transport.set(true);
                broker.close();
                assertEquals(503, get(client, base + "/ready"));
                assertEquals(404, get(client, base + "/metrics/extra"));
                assertEquals(405, client.send(HttpRequest.newBuilder(URI.create(base + "/ready"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            }
            assertThrows(IllegalArgumentException.class, () -> new OperationsServer(new InetSocketAddress("0.0.0.0", 0), metrics, () -> true));
        }
    }
    private int get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
