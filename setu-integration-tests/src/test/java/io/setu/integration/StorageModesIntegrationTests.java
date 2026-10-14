package io.setu.integration;

import io.setu.client.SetuClient;
import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.ErrorCode;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.BrokerRequestHandler;
import io.setu.server.tcp.TcpServer;
import io.setu.storage.log.StorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies normal end-to-end contracts for each configured storage mode. */
class StorageModesIntegrationTests {
    private static final String TOPIC = "orders";
    private static final String GROUP = "orders-readers";
    private static final int MAX_FRAME = 15 * 1024 * 1024;

    @TempDir Path tempDir;

    @Test
    void reusedClientBatchFramePreservesEarlierRecordsInBothStorageModes() throws Exception {
        for (StorageMode mode : StorageMode.values()) {
            try (DefaultBroker broker = new DefaultBroker(mode, mode == StorageMode.FILE ? tempDir.resolve("batches") : null, 1024);
                 TcpServer server = start(broker)) {
                server.start();
                try (SetuClient client = client(server.localAddress().getPort())) {
                    assertEquals(ErrorCode.NONE, client.createTopic(TOPIC, 1).errorCode());
                    assertEquals(ErrorCode.NONE, client.joinGroup(GROUP, TOPIC, "reader").errorCode());
                    assertEquals(0, client.produceBatch(TOPIC, 0, List.of(bytes("first"), bytes("batch"))).offset());
                    var earlier = client.fetch(GROUP, "reader", TOPIC, 0, 0, 1024).records();
                    assertEquals(2, client.produceBatch(TOPIC, 0, List.of(bytes("other"), bytes("value"))).offset());
                    assertEquals(4, client.produceBatch(TOPIC, 0, List.of(new byte[0], bytes("longer-value"))).offset());
                    assertEquals(List.of("first", "batch"), earlier.stream().map(record -> text(record.value())).toList());
                    assertEquals(List.of("first", "batch", "other", "value", "", "longer-value"),
                            client.fetch(GROUP, "reader", TOPIC, 0, 0, 1024).records().stream().map(record -> text(record.value())).toList());
                }
            }
        }
    }

    @Test
    void inMemoryBrokerReclaimsAcknowledgedRecordsAndNewMembersStartAtJoinTime() throws Exception {
        verifyMode(StorageMode.IN_MEMORY);
    }

    @Test
    void fileBrokerRetainsFullSequenceForLateMembers() throws Exception {
        verifyMode(StorageMode.FILE);
    }

    private void verifyMode(StorageMode mode) throws Exception {
        Path data = mode == StorageMode.FILE ? tempDir.resolve("file-data") : null;
        try (DefaultBroker broker = new DefaultBroker(mode, data, 1024, 1024 * 1024, 5_000, 10_000);
             TcpServer server = start(broker)) {
            server.start();
            int port = server.localAddress().getPort();
            try (SetuClient producer = client(port);
                 SetuClient first = client(port);
                 SetuClient second = client(port)) {
                assertEquals(ErrorCode.NONE, producer.createTopic(TOPIC, 1).errorCode());
                assertEquals(ErrorCode.NONE, first.joinGroup(GROUP, TOPIC, "reader-one").errorCode());
                assertEquals(ErrorCode.NONE, second.joinGroup(GROUP, TOPIC, "reader-two").errorCode());
                List<String> initial = List.of("order-1", "order-2", "order-3");
                for (String record : initial)
                    assertEquals(ErrorCode.NONE, producer.produce(TOPIC, 0, bytes(record)).errorCode());

                var firstRead = first.fetch(GROUP, "reader-one", TOPIC, 0, 0, 1024).records();
                var secondRead = second.fetch(GROUP, "reader-two", TOPIC, 0, 0, 1024).records();
                assertEquals(initial, firstRead.stream().map(record -> text(record.value())).toList());
                assertEquals(initial, secondRead.stream().map(record -> text(record.value())).toList());
                assertEquals(ErrorCode.NONE, first.acknowledge(GROUP, "reader-one", TOPIC, 0, 3).errorCode());
                assertEquals(ErrorCode.NONE, second.acknowledge(GROUP, "reader-two", TOPIC, 0, 3).errorCode());

                var rawHistory = producer.fetch(TOPIC, 0, 0, 1024).records();
                if (mode == StorageMode.IN_MEMORY) assertTrue(rawHistory.isEmpty());
                else assertEquals(initial, rawHistory.stream().map(record -> text(record.value())).toList());

                try (SetuClient late = client(port)) {
                    assertEquals(ErrorCode.NONE, late.joinGroup(GROUP, TOPIC, "late-reader").errorCode());
                    assertEquals(3, producer.produce(TOPIC, 0, bytes("order-4")).offset());
                    var lateRead = late.fetch(GROUP, "late-reader", TOPIC, 0, 0, 1024).records();
                    if (mode == StorageMode.IN_MEMORY) {
                        assertEquals(1, lateRead.size());
                        assertEquals(3, lateRead.getFirst().offset());
                        assertEquals("order-4", text(lateRead.getFirst().value()));
                    } else {
                        assertEquals(4, lateRead.size());
                        assertEquals(0, lateRead.getFirst().offset());
                        assertEquals("order-4", text(lateRead.getLast().value()));
                    }
                }
            }
        }
    }

    private static TcpServer start(DefaultBroker broker) {
        return new TcpServer(new InetSocketAddress("127.0.0.1", 0),
                new FramedConnectionHandler(new BrokerRequestHandler(broker)));
    }

    private static SetuClient client(int port) throws java.io.IOException {
        return SetuClient.connectPlain("127.0.0.1", port, 2_000, 5_000, MAX_FRAME);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
}
