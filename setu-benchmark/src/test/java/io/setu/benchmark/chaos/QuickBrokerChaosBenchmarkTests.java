package io.setu.benchmark.chaos;

import io.setu.client.SetuClient;
import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.ErrorCode;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.BrokerRequestHandler;
import io.setu.server.tcp.TcpServer;
import io.setu.storage.log.StorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Bounded CI chaos workload: concurrent producers, sixteen consumers, stalled-member eviction, and restart recovery. */
class QuickBrokerChaosBenchmarkTests {
    private static final int RECORDS = 20_000;
    private static final int PRODUCER_COUNT = 16;
    private static final int CONSUMER_COUNT = 16;
    private static final int MAX_FRAME = 15 * 1024 * 1024;
    private static final String TOPIC = "quick-chaos";
    private static final String GROUP = "quick-group";
    private static final int VALUE_BYTES = 256;

    @TempDir Path tempDir;

    @Test
    void measuresBothStorageModesWithMultipleConsumersAndBrokerRecovery() throws Exception {
        for (StorageMode mode : StorageMode.values()) runWorkload(mode);
    }

    private void runWorkload(StorageMode mode) throws Exception {
        Path data = tempDir.resolve(mode.name());
        DefaultBroker broker = new DefaultBroker(mode, mode == StorageMode.FILE ? data : null,
                VALUE_BYTES, 128 * 1024, 250, 10_000);
        TcpServer server = start(broker);
        List<SetuClient> clients = new ArrayList<>();
        try {
            SetuClient producer = connect(server, clients);
            assertEquals(ErrorCode.NONE, producer.createTopic(TOPIC, 1).errorCode());
            var consumers = new ArrayList<SetuClient>();
            for (int i = 0; i < CONSUMER_COUNT; i++) {
                SetuClient consumer = connect(server, clients);
                consumers.add(consumer);
                join(consumer, "consumer-" + i);
            }

            long started = System.nanoTime();
            Set<Long> offsets = ConcurrentHashMap.newKeySet();
            var executor = Executors.newFixedThreadPool(PRODUCER_COUNT);
            TcpServer producerServer = server;
            try {
                var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
                for (int producerId = 0; producerId < PRODUCER_COUNT; producerId++) {
                    int id = producerId;
                    tasks.add(() -> {
                        try (SetuClient concurrentProducer = connect(producerServer)) {
                            for (int i = 0; i < RECORDS / PRODUCER_COUNT; i++) {
                                byte[] value = new byte[VALUE_BYTES];
                                value[0] = (byte) (id + i);
                                var result = concurrentProducer.produce(TOPIC, 0, value);
                                assertEquals(ErrorCode.NONE, result.errorCode());
                                assertTrue(offsets.add(result.offset()), "duplicate offset " + result.offset());
                            }
                        }
                        return null;
                    });
                }
                for (var result : executor.invokeAll(tasks, 90, TimeUnit.SECONDS)) {
                    assertFalse(result.isCancelled(), "concurrent producers exceeded 90 seconds");
                    result.get();
                }
            } finally {
                executor.shutdownNow();
            }
            assertEquals(RECORDS, offsets.size());
            long produceNanos = System.nanoTime() - started;

            started = System.nanoTime();
            for (int i = 0; i < CONSUMER_COUNT - 1; i++)
                assertEquals(RECORDS, consumeAndAck(consumers.get(i), "consumer-" + i));
            var abandonedBatch = consumers.getLast().fetch(GROUP, "consumer-" + (CONSUMER_COUNT - 1),
                    TOPIC, 0, 0, 15 * 1024 * 1024);
            assertEquals(10_000, abandonedBatch.records().size());
            long consumeNanos = System.nanoTime() - started;

            consumers.getLast().close(); // inject a consumer drop with a pending acknowledgement
            clients.remove(consumers.getLast());
            Thread.sleep(350); // exceed acknowledgement expiry
            assertEquals(ErrorCode.NONE, producer.produce(TOPIC, 0, bytes("recovery-marker")).errorCode());
            assertEquals("recovery-marker", text(consumers.getFirst().fetch(GROUP, "consumer-0", TOPIC, 0,
                    RECORDS, 1024).records().getFirst().value()));
            assertEquals("recovery-marker", text(consumers.get(1).fetch(GROUP, "consumer-1", TOPIC, 0,
                    RECORDS, 1024).records().getFirst().value()));
            assertEquals(ErrorCode.NONE, consumers.getFirst().acknowledge(GROUP, "consumer-0", TOPIC, 0, RECORDS + 1).errorCode());
            assertEquals(ErrorCode.NONE, consumers.get(1).acknowledge(GROUP, "consumer-1", TOPIC, 0, RECORDS + 1).errorCode());

            server.close();
            broker.close();
            clients.forEach(QuickBrokerChaosBenchmarkTests::closeQuietly);
            clients.clear();
            started = System.nanoTime();
            broker = new DefaultBroker(mode, mode == StorageMode.FILE ? data : null,
                    VALUE_BYTES, 128 * 1024, 250, 10_000);
            long restartNanos = System.nanoTime() - started;
            server = start(broker);
            try (SetuClient recovered = connect(server)) {
                if (mode == StorageMode.FILE) {
                    assertEquals(1, recovered.metadata().topics().get(TOPIC));
                    assertEquals(RECORDS + 1, countRecords(recovered, RECORDS + 1));
                    assertEquals("recovery-marker", text(recovered.fetch(
                            TOPIC, 0, RECORDS, 1024).records().getFirst().value()));
                } else {
                    assertTrue(recovered.metadata().topics().isEmpty());
                }
            }

            long consumedRecords = (long) RECORDS * (CONSUMER_COUNT - 1) + abandonedBatch.records().size();
            System.out.printf("chaos-benchmark mode=%s producers=%d consumers=%d records=%d bytes_per_record=%d consumer_drop=evicted broker_restart_history_recovered=%s produce_records_per_sec=%.0f consumed_records_per_sec=%.0f restart_ms=%.2f%n",
                    mode.name().toLowerCase(), PRODUCER_COUNT, CONSUMER_COUNT, RECORDS, VALUE_BYTES, mode == StorageMode.FILE,
                    RECORDS / (produceNanos / 1_000_000_000.0), consumedRecords / (consumeNanos / 1_000_000_000.0),
                    restartNanos / 1_000_000.0);
        } finally {
            server.close();
            broker.close();
            clients.forEach(QuickBrokerChaosBenchmarkTests::closeQuietly);
        }
    }

    private static int consumeAndAck(SetuClient client, String member) throws IOException {
        int total = 0;
        long next = 0;
        while (next < RECORDS) {
            var response = client.fetch(GROUP, member, TOPIC, 0, next, 15 * 1024 * 1024);
            assertEquals(ErrorCode.NONE, response.errorCode());
            assertFalse(response.records().isEmpty());
            total += response.records().size();
            next = response.records().getLast().offset() + 1;
            assertEquals(ErrorCode.NONE, client.acknowledge(GROUP, member, TOPIC, 0, next).errorCode());
        }
        return total;
    }

    private static int countRecords(SetuClient client, int expected) throws IOException {
        int count = 0;
        long offset = 0;
        while (offset < expected) {
            var response = client.fetch(TOPIC, 0, offset, 15 * 1024 * 1024);
            assertFalse(response.records().isEmpty(), "recovery stopped at offset " + offset);
            count += response.records().size();
            offset = response.records().getLast().offset() + 1;
        }
        return count;
    }

    private static void join(SetuClient client, String member) throws IOException {
        assertEquals(ErrorCode.NONE, client.joinGroup(GROUP, TOPIC, member).errorCode());
    }

    private static TcpServer start(DefaultBroker broker) throws IOException {
        TcpServer server = new TcpServer(new InetSocketAddress("127.0.0.1", 0),
                new FramedConnectionHandler(new BrokerRequestHandler(broker)));
        server.start();
        return server;
    }

    private static SetuClient connect(TcpServer server, List<SetuClient> clients) throws IOException {
        SetuClient client = connect(server);
        clients.add(client);
        return client;
    }

    private static SetuClient connect(TcpServer server) throws IOException {
        return SetuClient.connectPlain("127.0.0.1", server.localAddress().getPort(), 2_000, 10_000, MAX_FRAME);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
    private static void closeQuietly(SetuClient client) {
        try { client.close(); } catch (IOException ignored) { }
    }
}

