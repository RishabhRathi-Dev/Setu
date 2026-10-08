package io.setu.core.broker;

import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.Command;
import io.setu.core.protocol.ProtocolRecord;
import io.setu.storage.log.FileMessageLog;
import io.setu.storage.log.StorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultBrokerTests {
    @TempDir Path tempDir;

    @Test
    void topicsAndRecordsSurviveBrokerRestart() throws Exception {
        try (DefaultBroker broker = new DefaultBroker(tempDir, 1024)) {
            broker.execute(new BrokerRequest(1, Command.CREATE_TOPIC, "orders", -1, -1, -1,
                    null, 2, null, null));
            BrokerResponse written = broker.execute(new BrokerRequest(2, Command.PRODUCE, "orders", 1,
                    -1, -1, "order-1".getBytes(), 0, null, null));
            assertEquals(0, written.offset());
        }
        try (DefaultBroker recovered = new DefaultBroker(tempDir, 1024)) {
            BrokerResponse response = recovered.execute(new BrokerRequest(3, Command.FETCH, "orders", 1,
                    0, 1024, null, 0, null, null));
            assertEquals(1, response.records().size());
            ProtocolRecord record = response.records().getFirst();
            assertEquals(0, record.offset());
            assertArrayEquals("order-1".getBytes(), record.value());
        }
    }

    @Test
    void memoryStorageReclaimsOnlyAfterEveryMemberAcknowledgesAndNewMembersStartAtJoin() throws Exception {
        try (DefaultBroker broker = new DefaultBroker(1024)) {
            createTopic(broker);
            join(broker, "g", "first");
            join(broker, "g", "second");
            assertEquals(0, produce(broker, "before-1").offset());
            assertEquals(1, produce(broker, "before-2").offset());

            assertEquals(2, fetch(broker, "g", "first", 0).records().size());
            acknowledge(broker, "g", "first", 2);
            assertEquals(2, fetch(broker, null, null, 0).records().size(),
                    "the second member has not acknowledged, so its records must remain");

            assertEquals(2, fetch(broker, "g", "second", 0).records().size());
            acknowledge(broker, "g", "second", 2);
            assertTrue(fetch(broker, null, null, 0).records().isEmpty(),
                    "records acknowledged by every current member should be reclaimed");

            join(broker, "g", "late");
            assertEquals(2, produce(broker, "after-join").offset());
            var lateBatch = fetch(broker, "g", "late", 0).records();
            assertEquals(1, lateBatch.size());
            assertEquals(2, lateBatch.getFirst().offset());
            assertArrayEquals("after-join".getBytes(), lateBatch.getFirst().value());
        }
    }

    @Test
    void fileStorageLetsNewMembersReadTheWholeLog() throws Exception {
        try (DefaultBroker broker = new DefaultBroker(tempDir, 1024)) {
            createTopic(broker);
            produce(broker, "before-join");
            join(broker, "g", "late");
            var records = fetch(broker, "g", "late", 0).records();
            assertEquals(1, records.size());
            assertEquals(0, records.getFirst().offset());
            assertArrayEquals("before-join".getBytes(), records.getFirst().value());
        }
    }

    @Test
    void brokerRotatesFileSegmentsAndRecoversRecordsInOffsetOrder() throws Exception {
        try (DefaultBroker broker = new DefaultBroker(StorageMode.FILE, tempDir, 1024, 10, 5_000, 5_000)) {
            createTopic(broker);
            assertEquals(0, produce(broker, "abc").offset());
            assertEquals(1, produce(broker, "def").offset());
        }

        Path partition = tempDir.resolve("orders").resolve("partition-0");
        assertTrue(Files.exists(partition.resolve("000000000000.log")));
        assertTrue(Files.exists(partition.resolve("000000000001.log")));

        try (DefaultBroker recovered = new DefaultBroker(StorageMode.FILE, tempDir, 1024, 10, 5_000, 5_000)) {
            var records = fetch(recovered, null, null, 0).records();
            assertEquals(2, records.size());
            assertEquals(0, records.getFirst().offset());
            assertArrayEquals("abc".getBytes(), records.getFirst().value());
            assertEquals(1, records.getLast().offset());
            assertArrayEquals("def".getBytes(), records.getLast().value());
        }
    }

    @Test
    void brokerRecoversLegacySingleFilePartitionsAsFirstSegment() throws Exception {
        Path partition = tempDir.resolve("orders").resolve("partition-0");
        Files.createDirectories(partition);
        try (FileMessageLog legacy = new FileMessageLog(partition.resolve("000000000000.log"), 1024)) {
            legacy.append("legacy-record".getBytes());
        }

        try (DefaultBroker broker = new DefaultBroker(tempDir, 1024)) {
            var records = fetch(broker, null, null, 0).records();
            assertEquals(1, records.size());
            assertEquals(0, records.getFirst().offset());
            assertArrayEquals("legacy-record".getBytes(), records.getFirst().value());
            assertEquals(1, produce(broker, "next-record").offset());
        }
    }

    @Test
    void memberWithUnacknowledgedRecordsExpiresAtAckDeadline() throws Exception {
        try (DefaultBroker broker = new DefaultBroker(StorageMode.IN_MEMORY, null, 1024, 60, 5_000)) {
            createTopic(broker);
            join(broker, "g", "slow");
            produce(broker, "pending");
            assertEquals(1, fetch(broker, "g", "slow", 0).records().size());

            Thread.sleep(150);
            produce(broker, "after-timeout"); // Request processing expires the stalled member before append.
            BrokerException failure = assertThrows(BrokerException.class,
                    () -> fetch(broker, "g", "slow", 0));
            assertTrue(failure.getMessage().contains("not registered"));
        }
    }

    @Test
    void inactiveMemberIsRemovedEvenWithoutAnOutstandingFetch() throws Exception {
        try (DefaultBroker broker = new DefaultBroker(StorageMode.IN_MEMORY, null, 1024, 5_000, 60)) {
            createTopic(broker);
            join(broker, "g", "idle");
            Thread.sleep(150);
            BrokerException failure = assertThrows(BrokerException.class,
                    () -> broker.execute(new BrokerRequest(8, Command.HEARTBEAT, null, -1, -1, -1,
                            null, 0, "g", "idle")));
            assertTrue(failure.getMessage().contains("not registered"));
        }
    }

    @Test
    void memberCannotAcknowledgeRecordsItHasNotFetched() throws Exception {
        try (DefaultBroker broker = new DefaultBroker(1024)) {
            createTopic(broker);
            join(broker, "g", "member");
            produce(broker, "one");
            BrokerException failure = assertThrows(BrokerException.class,
                    () -> acknowledge(broker, "g", "member", 1));
            assertTrue(failure.getMessage().contains("not fetched"));
        }
    }

    private void createTopic(DefaultBroker broker) throws Exception {
        broker.execute(new BrokerRequest(1, Command.CREATE_TOPIC, "orders", -1, -1, -1,
                null, 1, null, null));
    }

    private static void join(DefaultBroker broker, String group, String member) throws Exception {
        broker.execute(new BrokerRequest(2, Command.JOIN_GROUP, "orders", -1, -1, -1,
                null, 0, group, member));
    }

    private static BrokerResponse produce(DefaultBroker broker, String value) throws Exception {
        return broker.execute(new BrokerRequest(3, Command.PRODUCE, "orders", 0, -1, -1,
                value.getBytes(), 0, null, null));
    }

    private static BrokerResponse fetch(DefaultBroker broker, String group, String member, long offset) throws Exception {
        return broker.execute(new BrokerRequest(4, Command.FETCH, "orders", 0, offset, 1024,
                null, 0, group, member));
    }

    private static void acknowledge(DefaultBroker broker, String group, String member, long nextOffset) throws Exception {
        broker.execute(new BrokerRequest(5, Command.COMMIT_OFFSET, "orders", 0, nextOffset, 0,
                null, 0, group, member));
    }
}
