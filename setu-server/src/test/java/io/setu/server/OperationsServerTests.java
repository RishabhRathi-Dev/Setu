package io.setu.server;

import io.setu.core.protocol.BufferBudgetExceededException;
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
                assertTrue(scrape.body().contains("setu_jvm_heap_used_bytes "));
                assertTrue(scrape.body().contains("setu_process_uptime_seconds "));
                assertFalse(scrape.body().contains("NaN"));
                assertFalse(scrape.body().contains("private-topic"));
                assertEquals(200, get(client, base + "/ready"));
                transport.set(false);
                assertEquals(503, get(client, base + "/ready"));
                assertTrue(client.send(HttpRequest.newBuilder(URI.create(base + "/metrics")).build(),
                        HttpResponse.BodyHandlers.ofString()).body().contains("setu_ready 0\n"));
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

    @Test void trafficCountsSuccessfulResponsesAndPayloadsRatherThanRequestsOrUniqueRecords() throws Exception {
        try (var broker = new DefaultBroker(1024)) {
            var metrics = new BrokerMetrics(broker);
            metrics.execute(new BrokerRequest(1, Command.CREATE_TOPIC, "orders", 0, 0, 0, null, 1, null, null));
            metrics.execute(new BrokerRequest(2, Command.JOIN_GROUP, "orders", 0, 0, 0, null, 0, "group", "member"));
            metrics.execute(new BrokerRequest(3, Command.PRODUCE_BATCH, "orders", 0, 0, 0, null, 0, null, null,
                    java.util.List.of(new byte[3], new byte[5])));
            metrics.execute(new BrokerRequest(4, Command.PRODUCE, "orders", 0, 0, 0, new byte[0], 0, null, null));
            assertThrows(io.setu.core.broker.BrokerException.class, () -> metrics.execute(new BrokerRequest(
                    5, Command.PRODUCE, "missing", 0, 0, 0, new byte[7], 0, null, null)));
            var fetch = new BrokerRequest(6, Command.FETCH, "orders", 0, 0, 1024, null, 0, "group", "member");
            assertEquals(3, metrics.execute(fetch).records().size());
            metrics.execute(fetch); // Repeated delivery contributes again; this is not unique consumption.
            try (var credit = new ResponseBuffers(new BufferBudget(1)); var context = credit.activate()) {
                assertThrows(BufferBudgetExceededException.class, () -> metrics.execute(fetch));
            }
            String scrape = metrics.scrape();
            assertTrue(scrape.contains("setu_requests_total{command=\"FETCH\",error=\"QUOTA_EXCEEDED\"} 1\n"));
            assertTrue(scrape.contains("setu_produce_response_records_total{command=\"PRODUCE_BATCH\",acknowledgement=\"DURABLE\"} 2\n"));
            assertTrue(scrape.contains("setu_produce_response_payload_bytes_total{command=\"PRODUCE_BATCH\",acknowledgement=\"DURABLE\"} 8\n"));
            assertTrue(scrape.contains("setu_produce_response_records_total{command=\"PRODUCE\",acknowledgement=\"DURABLE\"} 1\n"));
            assertTrue(scrape.contains("setu_produce_response_payload_bytes_total{command=\"PRODUCE\",acknowledgement=\"DURABLE\"} 0\n"));
            assertTrue(scrape.contains("setu_fetch_response_records_total 6\n"));
            assertTrue(scrape.contains("setu_fetch_response_payload_bytes_total 16\n"));
        }
    }

    @Test void identifiedRetryResponsesAreCountedAgainAndAcceptedWritesStaySeparate(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        try (var broker = new DefaultBroker(directory, 1024)) {
            var metrics = new BrokerMetrics(broker);
            metrics.execute(new BrokerRequest(1, Command.CREATE_TOPIC, "orders", 0, 0, 0, null, 1, null, null));
            var write = new BrokerRequest(2, Command.PRODUCE_IDEMPOTENT, "orders", 0, 0, 0, null, 0, null, null,
                    java.util.List.of(new byte[3])).withOptions(new RequestOptions(
                            new ProducerIdentity("writer", 0, 0), null, AcknowledgementMode.DURABLE, -1));
            assertEquals(0, metrics.execute(write).offset());
            assertEquals(0, metrics.execute(write).offset());
            var accepted = new BrokerRequest(3, Command.PRODUCE_CONFIGURED, "orders", 0, 0, 0, null, 0, null, null,
                    java.util.List.of(new byte[5])).withOptions(new RequestOptions(null, null, AcknowledgementMode.ACCEPTED, -1));
            assertEquals(ErrorCode.NONE, metrics.execute(accepted).errorCode());
            String scrape = metrics.scrape();
            assertTrue(scrape.contains("setu_produce_response_records_total{command=\"PRODUCE_IDEMPOTENT\",acknowledgement=\"DURABLE\"} 2\n"));
            assertTrue(scrape.contains("setu_produce_response_records_total{command=\"PRODUCE_CONFIGURED\",acknowledgement=\"ACCEPTED\"} 1\n"));
        }
    }
    private int get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
