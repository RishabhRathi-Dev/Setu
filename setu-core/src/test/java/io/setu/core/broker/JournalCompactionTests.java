package io.setu.core.broker;

import io.setu.core.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JournalCompactionTests {
    @TempDir Path directory;
    private BrokerRequest request(Command command, String topic) {
        return new BrokerRequest(1, command, topic, 0, 0, 1024, null, 1, "group", "member");
    }
    private BrokerRequest write(long epoch, long sequence) {
        return new BrokerRequest(1, Command.PRODUCE_IDEMPOTENT, "output", 0, -1, -1,
                null, 0, null, null, List.of(new byte[]{7})).withOptions(
                new RequestOptions(new ProducerIdentity("producer", epoch, sequence), null, AcknowledgementMode.DURABLE, -1));
    }
    private long initialize(DefaultBroker broker) throws Exception {
        broker.execute(request(Command.CREATE_TOPIC, "output"));
        long generation = broker.execute(request(Command.JOIN_MANAGED_GROUP, "output")).assignment().generation();
        broker.execute(write(2, 0));
        broker.execute(request(Command.FETCH, "output").withGeneration(generation));
        broker.execute(new BrokerRequest(1, Command.COMMIT_OFFSET, "output", 0, 1, 0,
                null, 0, "group", "member").withGeneration(generation));
        return generation;
    }

    @Test void compactionPreservesProgressGenerationDeduplicationAndFencing() throws Exception {
        long generation;
        try (var broker = new DefaultBroker(directory, 1024)) {
            generation = initialize(broker);
            for (int i = 0; i < 3; i++) broker.compactJournal();
            assertEquals(3, broker.journalCompactions());
        }
        try (var broker = new DefaultBroker(directory, 1024)) {
            var assignment = broker.execute(request(Command.GROUP_ASSIGNMENT, "output")).assignment();
            assertEquals(generation, assignment.generation());
            assertEquals(1, assignment.nextOffsets().get(0));
            assertEquals(0, broker.execute(write(2, 0)).offset());
            assertEquals(ErrorCode.PRODUCER_FENCED, assertThrows(BrokerException.class,
                    () -> broker.execute(write(1, 0))).errorCode());
            assertEquals(1, broker.execute(write(2, 1)).offset());
        }
        try (var paths = Files.list(directory.resolve("@consumer-state"))) {
            assertEquals(1, paths.filter(Files::isDirectory).count(), () -> {
                try (var entries = Files.walk(directory.resolve("@consumer-state"))) { return entries.toList().toString(); }
                catch (Exception error) { return error.toString(); }
            });
        }
    }

    @Test void failureOnEitherSideOfManifestPublicationRecoversCompleteState() throws Exception {
        for (String stage : List.of("checkpoint-written", "checkpoint-forced", "manifest-forced",
                "manifest-published", "before-cleanup", "after-cleanup")) {
            Path data = directory.resolve(stage);
            try (var broker = new DefaultBroker(data, 1024)) {
                initialize(broker);
                broker.journalFault(point -> { if (point.equals(stage)) throw new java.io.IOException("injected"); });
                assertThrows(java.io.IOException.class, broker::compactJournal);
                assertFalse(broker.isReady());
            }
            try (var broker = new DefaultBroker(data, 1024)) {
                assertEquals(0, broker.execute(write(2, 0)).offset());
                assertEquals(1, broker.execute(request(Command.GROUP_ASSIGNMENT, "output")).assignment().nextOffsets().get(0));
                broker.compactJournal();
            }
        }
    }

    @Test void checkpointCorruptionFailsClosedAndDoesNotFallBackToOldHistory() throws Exception {
        try (var broker = new DefaultBroker(directory, 1024)) { initialize(broker); broker.compactJournal(); }
        Path root = directory.resolve("@consumer-state");
        Path checkpoint = root.resolve(Files.readString(root.resolve("CURRENT"))).resolve("checkpoint");
        byte[] bytes = Files.readAllBytes(checkpoint);
        bytes[bytes.length - 1] ^= 1;
        Files.write(checkpoint, bytes);
        assertThrows(java.io.IOException.class, () -> new DefaultBroker(directory, 1024));
    }

    @Test void departedGroupsAndExplicitMembersSurviveSnapshotAndTailReplay() throws Exception {
        try (var broker = new DefaultBroker(directory, 1024)) {
            initialize(broker);
            broker.execute(request(Command.LEAVE_GROUP, "output"));
            broker.execute(request(Command.CREATE_TOPIC, "explicit"));
            broker.execute(request(Command.JOIN_GROUP, "explicit"));
            broker.compactJournal();
            broker.execute(write(2, 1));
        }
        try (var broker = new DefaultBroker(directory, 1024)) {
            assertEquals(1, broker.execute(write(2, 1)).offset());
            assertEquals(0, broker.execute(request(Command.FETCH_OFFSET, "explicit")).offset());
            var joined = new BrokerRequest(1, Command.JOIN_MANAGED_GROUP, "output", 0, 0, 0,
                    null, 0, "group", "replacement");
            assertEquals(1, broker.execute(joined).assignment().nextOffsets().get(0));
        }
    }

    @Test void automaticThresholdCompactsAndUnacknowledgedFetchDoesNotRecover() throws Exception {
        long generation;
        try (var broker = new DefaultBroker(directory, 1024)) {
            generation = initialize(broker);
            broker.configureJournalCompaction(1);
            broker.execute(write(2, 1));
            broker.execute(new BrokerRequest(1, Command.FETCH, "output", 0, 1, 1024, null, 0,
                    "group", "member").withGeneration(generation));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (broker.journalCompactions() == 0 && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(broker.journalCompactions() > 0);
        }
        try (var broker = new DefaultBroker(directory, 1024)) {
            assertThrows(BrokerException.class, () -> broker.execute(new BrokerRequest(1, Command.COMMIT_OFFSET,
                    "output", 0, 2, 0, null, 0, "group", "member").withGeneration(generation)));
            assertEquals(1, broker.execute(request(Command.GROUP_ASSIGNMENT, "output")).assignment().nextOffsets().get(0));
        }
    }

    @Test void seededRetriesCompactionsAndRestartsMatchAnAppendOnlyModel() throws Exception {
        var random = new java.util.Random(6142026);
        long sequence = 0;
        for (int restart = 0; restart < 4; restart++) {
            try (var broker = new DefaultBroker(directory, 1024)) {
                if (restart == 0) broker.execute(request(Command.CREATE_TOPIC, "output"));
                for (int operation = 0; operation < 12; operation++) {
                    assertEquals(sequence, broker.execute(write(0, sequence)).offset());
                    if (random.nextBoolean()) assertEquals(sequence, broker.execute(write(0, sequence)).offset());
                    sequence++;
                    if (random.nextInt(3) == 0) broker.compactJournal();
                }
                assertEquals(sequence, broker.execute(new BrokerRequest(1, Command.FETCH, "output", 0,
                        0, 1024, null, 0, null, null)).records().size());
            }
        }
    }

    @Test void missingAuthoritativePointerCannotResetCompactedState() throws Exception {
        try (var broker = new DefaultBroker(directory, 1024)) { initialize(broker); broker.compactJournal(); }
        Files.delete(directory.resolve("@consumer-state/CURRENT"));
        assertThrows(java.io.IOException.class, () -> new DefaultBroker(directory, 1024));
    }
}
