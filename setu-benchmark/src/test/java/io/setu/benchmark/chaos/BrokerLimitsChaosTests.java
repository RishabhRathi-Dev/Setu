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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Bounded saturation/recovery exercise intended to probe resource and batching limits in CI. */
class BrokerLimitsChaosTests {
    private static final int PRODUCERS = 16;
    private static final int CONSUMERS = 16;
    private static final int RECORDS_PER_PRODUCER = 1_000;
    private static final int RECORDS = PRODUCERS * RECORDS_PER_PRODUCER;
    private static final int WORKLOAD_TIMEOUT_SECONDS = 300;
    private static final int MAX_FRAME = 15 * 1024 * 1024;
    // Keep sixteen concurrent 1 KiB fanout readers inside the shared response admission budget.
    private static final int FETCH_PAGE_BYTES = 1024 * 1024;
    private static final String TOPIC = "limit-chaos";
    private static final String GROUP = "limit-group";

    @TempDir Path tempDir;

    @Test
    void sustainsConcurrentProducersConsumerFanoutSegmentRotationAndRecoveryInBothModes() throws Exception {
        for (StorageMode mode : StorageMode.values()) exerciseLimits(mode);
    }

    private void exerciseLimits(StorageMode mode) throws Exception {
        Path data = tempDir.resolve(mode.name());
        DefaultBroker broker = new DefaultBroker(mode, mode == StorageMode.FILE ? data : null,
                1024, 32 * 1024, 250, 600_000);
        TcpServer server = start(broker);
        var consumers = new ArrayList<SetuClient>();
        SetuClient admin = null;
        try {
            admin = connect(server);
            assertEquals(ErrorCode.NONE, admin.createTopic(TOPIC, 1).errorCode());
            assertEquals(ErrorCode.BAD_REQUEST,
                    admin.produce(TOPIC, 0, new byte[1_025]).errorCode(),
                    "a request just beyond the configured record limit must be rejected cleanly");
            assertEquals(ErrorCode.NONE, admin.createTopic("record-boundary", 1).errorCode());
            assertEquals(ErrorCode.NONE, admin.joinGroup("boundary-group", "record-boundary", "boundary-member").errorCode());
            assertEquals(ErrorCode.NONE, admin.produce("record-boundary", 0, new byte[1_024]).errorCode(),
                    "a record exactly at the configured maximum must be accepted");
            assertEquals(1_024, admin.fetch("boundary-group", "boundary-member",
                    "record-boundary", 0, 0, 1_024).records().getFirst().value().length);
            assertEquals(ErrorCode.BAD_REQUEST,
                    admin.produce(TOPIC, 0, new byte[1_025]).errorCode(),
                    "a request just beyond the configured record limit must be rejected cleanly");
            for (int i = 0; i < CONSUMERS; i++) {
                SetuClient consumer = connect(server);
                consumers.add(consumer);
                assertEquals(ErrorCode.NONE,
                        consumer.joinGroup(GROUP, TOPIC, "consumer-" + i).errorCode());
            }

            Set<Long> offsets = ConcurrentHashMap.newKeySet();
            var executor = Executors.newFixedThreadPool(PRODUCERS + CONSUMERS - 1);
            TcpServer producerServer = server;
            try {
                var tasks = new ArrayList<java.util.concurrent.Callable<Integer>>();
                for (int producerId = 0; producerId < PRODUCERS; producerId++) {
                    int id = producerId;
                    tasks.add(() -> {
                        try (SetuClient producer = connect(producerServer)) {
                            for (int i = 0; i < RECORDS_PER_PRODUCER; i++) {
                                byte[] value = ("producer=" + id + ";record=" + i)
                                        .getBytes(StandardCharsets.UTF_8);
                                byte[] payload = new byte[1024];
                                System.arraycopy(value, 0, payload, 0, value.length);
                                java.util.Arrays.fill(payload, value.length, payload.length, (byte) id);
                                var result = producer.produce(TOPIC, 0, payload);
                                assertEquals(ErrorCode.NONE, result.errorCode());
                                assertTrue(offsets.add(result.offset()), "duplicate logical offset " + result.offset());
                            }
                        }
                        return -1;
                    });
                }
                for (int i = 0; i < CONSUMERS - 1; i++) {
                    int consumerIndex = i;
                    tasks.add(() -> {
                        SetuClient consumer = consumers.get(consumerIndex);
                        String member = "consumer-" + consumerIndex;
                        long next = 0;
                        int consumed = 0;
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WORKLOAD_TIMEOUT_SECONDS);
                        while (next < RECORDS) {
                            var batch = consumer.fetch(GROUP, member, TOPIC, 0, next, FETCH_PAGE_BYTES);
                            assertEquals(ErrorCode.NONE, batch.errorCode());
                            if (batch.records().isEmpty()) {
                                if (System.nanoTime() >= deadline)
                                    throw new AssertionError("consumer timed out at offset " + next);
                                Thread.sleep(1);
                                continue;
                            }
                            consumed += batch.records().size();
                            next = batch.records().getLast().offset() + 1;
                            assertEquals(ErrorCode.NONE,
                                    consumer.acknowledge(GROUP, member, TOPIC, 0, next).errorCode());
                        }
                        return consumed;
                    });
                }
                var futures = executor.invokeAll(tasks, WORKLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                for (int i = 0; i < futures.size(); i++) {
                    var future = futures.get(i);
                    assertFalse(future.isCancelled(), "concurrent broker workload exceeded five minutes in " + mode);
                    int completed = future.get();
                    if (i >= PRODUCERS) assertEquals(RECORDS, completed, "consumer " + (i - PRODUCERS));
                }
            } finally {
                executor.shutdownNow();
            }
            assertEquals(RECORDS, offsets.size());
            for (long offset = 0; offset < RECORDS; offset++)
                assertTrue(offsets.contains(offset), "missing logical offset " + offset);

            // The final member is deliberately slow and is then disconnected with a pending acknowledgement.
            String slowMember = "consumer-" + (CONSUMERS - 1);
            var slowBatch = consumers.getLast().fetch(GROUP, slowMember, TOPIC, 0, 0, 15 * 1024 * 1024);
            assertFalse(slowBatch.records().isEmpty());
            consumers.getLast().close();
            Thread.sleep(350);
            try (SetuClient producer = connect(server)) {
                assertEquals(ErrorCode.NONE,
                        producer.produce(TOPIC, 0, "post-expiry-marker".getBytes(StandardCharsets.UTF_8)).errorCode());
            }
            try (SetuClient observer = connect(server)) {
                assertEquals(ErrorCode.BAD_REQUEST, observer.heartbeat(GROUP, slowMember).errorCode());
                var last = observer.fetch(GROUP, "consumer-0", TOPIC, 0, RECORDS, 1024);
                assertEquals("post-expiry-marker", new String(last.records().getFirst().value(), StandardCharsets.UTF_8));
            }

            server.close();
            broker.close();
            closeAll(consumers);
            consumers.clear();
            broker = new DefaultBroker(mode, mode == StorageMode.FILE ? data : null,
                    1024, 32 * 1024, 250, 600_000);
            server = start(broker);
            try (SetuClient recovered = connect(server)) {
                if (mode == StorageMode.FILE) {
                    assertEquals(1, recovered.metadata().topics().get(TOPIC));
                    assertEquals(RECORDS + 1, countRecords(recovered, RECORDS + 1));
                    assertEquals("post-expiry-marker", new String(recovered.fetch(
                            TOPIC, 0, RECORDS, 1024).records().getFirst().value(), StandardCharsets.UTF_8));
                } else {
                    assertTrue(recovered.metadata().topics().isEmpty());
                }
            }
        } finally {
            server.close();
            broker.close();
            closeAll(consumers);
            closeQuietly(admin);
        }
    }

    private static TcpServer start(DefaultBroker broker) throws IOException {
        TcpServer server = new TcpServer(new InetSocketAddress("127.0.0.1", 0),
                new FramedConnectionHandler(new BrokerRequestHandler(broker)));
        server.start();
        return server;
    }

    private static SetuClient connect(TcpServer server) throws IOException {
        return SetuClient.connectPlain("127.0.0.1", server.localAddress().getPort(), 2_000, 10_000, MAX_FRAME);
    }

    private static int countRecords(SetuClient client, int expected) throws IOException {
        int count = 0;
        long offset = 0;
        while (offset < expected) {
            var batch = client.fetch(TOPIC, 0, offset, 15 * 1024 * 1024);
            assertFalse(batch.records().isEmpty(), "recovery stopped at offset " + offset);
            count += batch.records().size();
            offset = batch.records().getLast().offset() + 1;
        }
        return count;
    }

    private static void closeAll(Iterable<SetuClient> clients) {
        for (SetuClient client : clients) closeQuietly(client);
    }

    private static void closeQuietly(SetuClient client) {
        try { client.close(); } catch (IOException ignored) { }
    }
}

