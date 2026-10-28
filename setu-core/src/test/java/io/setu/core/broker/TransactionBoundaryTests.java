package io.setu.core.broker;

import io.setu.core.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.setu.core.broker.DefaultBroker.TransactionStage.*;
import static org.junit.jupiter.api.Assertions.*;

class TransactionBoundaryTests {
    @TempDir Path directory;

    private static BrokerRequest request(Command command, String topic, long offset) {
        return new BrokerRequest(1, command, topic, 0, offset, 1024, new byte[]{7}, 1, "group", "member");
    }

    private static BrokerRequest transaction(long generation) {
        return new BrokerRequest(1, Command.TRANSACT, "output", 0, -1, -1, null, 0, null, null,
                List.of(new byte[]{8}, new byte[]{9})).withOptions(new RequestOptions(
                new ProducerIdentity("processor", 0, 0), new ConsumerCommit("group", "member", "input", 0, 1, generation),
                AcknowledgementMode.DURABLE, -1));
    }

    private static long initialize(DefaultBroker broker) throws Exception {
        broker.configureJournalCompaction(0);
        broker.execute(request(Command.CREATE_TOPIC, "input", 0));
        broker.execute(request(Command.CREATE_TOPIC, "output", 0));
        long generation = broker.execute(request(Command.JOIN_MANAGED_GROUP, "input", 0)).assignment().generation();
        broker.execute(request(Command.PRODUCE, "input", 0));
        broker.execute(request(Command.FETCH, "input", 0).withGeneration(generation));
        return generation;
    }

    private static void verifyCommitted(DefaultBroker broker, long generation) throws Exception {
        assertEquals(0, broker.execute(transaction(generation)).offset());
        assertEquals(0, broker.execute(transaction(generation)).offset());
        var output = broker.execute(new BrokerRequest(1, Command.FETCH, "output", 0,
                0, 1024, null, 0, null, null)).records();
        assertEquals(2, output.size());
        assertArrayEquals(new byte[]{8}, output.get(0).value());
        assertArrayEquals(new byte[]{9}, output.get(1).value());
        assertEquals(1, broker.execute(request(Command.GROUP_ASSIGNMENT, "input", 0)).assignment().nextOffsets().get(0));
    }

    @Test void injectedFailuresBeforeAndAfterTheCommitDecisionHaveDistinctOutcomes() throws Exception {
        for (var stage : DefaultBroker.TransactionStage.values()) {
            Path data = directory.resolve(stage.name());
            long generation;
            try (var broker = new DefaultBroker(data, 1024)) {
                generation = initialize(broker);
                broker.transactionFault(point -> { if (point == stage) throw new IOException("injected " + point); });
                assertThrows(IOException.class, () -> broker.execute(transaction(generation)));
                if (stage == BEFORE_JOURNAL) {
                    assertTrue(broker.isReady());
                    assertEquals(0, broker.execute(request(Command.GROUP_ASSIGNMENT, "input", 0)).assignment().nextOffsets().get(0));
                    assertTrue(broker.execute(new BrokerRequest(1, Command.FETCH, "output", 0,
                            0, 1024, null, 0, null, null)).records().isEmpty());
                    broker.transactionFault(point -> { });
                    verifyCommitted(broker, generation);
                } else {
                    assertFalse(broker.isReady());
                    assertThrows(IOException.class, () -> broker.execute(request(Command.METADATA, "input", 0)));
                }
            }
            try (var broker = new DefaultBroker(data, 1024)) { verifyCommitted(broker, generation); }
        }
    }

    @Test void processExitAtEachCommittedBoundaryRecoversWithoutCloseOrShutdownHooks() throws Exception {
        for (var stage : List.of(JOURNAL_FORCED, OUTPUT_FORCED, INPUT_APPLIED, BEFORE_REPLY)) {
            Path data = directory.resolve(stage.name());
            Path output = directory.resolve(stage.name() + ".txt");
            var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
            command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    CrashWriter.class.getName(), data.toString(), stage.name()));
            Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {
                assertTrue(child.waitFor(30, TimeUnit.SECONDS), "transaction child timed out");
                assertEquals(23, child.exitValue(), () -> {
                    try { return Files.readString(output); } catch (IOException failure) { return failure.toString(); }
                });
            } finally { if (child.isAlive()) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS); }
            try (var broker = new DefaultBroker(data, 1024)) {
                long generation = broker.execute(request(Command.GROUP_ASSIGNMENT, "input", 0)).assignment().generation();
                verifyCommitted(broker, generation);
                broker.compactJournal();
            }
            try (var broker = new DefaultBroker(data, 1024)) {
                verifyCommitted(broker, broker.execute(request(Command.GROUP_ASSIGNMENT, "input", 0)).assignment().generation());
            }
        }
    }

    @Test void fetchAndProgressReadersCannotObserveOutputBeforeInputProgressIsApplied() throws Exception {
        var paused = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var broker = new DefaultBroker(directory, 1024); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            long generation = initialize(broker);
            broker.transactionFault(stage -> {
                if (stage != OUTPUT_FORCED) return;
                paused.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("pause timed out"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
            });
            var write = workers.submit(() -> broker.execute(transaction(generation)));
            try {
                assertTrue(paused.await(3, TimeUnit.SECONDS));
                var readersStarted = new CountDownLatch(2);
                var fetch = workers.submit(() -> {
                    readersStarted.countDown();
                    return broker.execute(new BrokerRequest(1, Command.FETCH, "output", 0, 0, 1024, null, 0, null, null));
                });
                var progress = workers.submit(() -> {
                    readersStarted.countDown();
                    return broker.execute(request(Command.GROUP_ASSIGNMENT, "input", 0));
                });
                assertTrue(readersStarted.await(3, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> fetch.get(100, TimeUnit.MILLISECONDS));
                assertThrows(TimeoutException.class, () -> progress.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                assertEquals(0, write.get(3, TimeUnit.SECONDS).offset());
                assertEquals(2, fetch.get(3, TimeUnit.SECONDS).records().size());
                assertEquals(1, progress.get(3, TimeUnit.SECONDS).assignment().nextOffsets().get(0));
            } finally { release.countDown(); }
        }
    }

    @Test void reassignmentRejectsStaleTransactionBeforeItsJournalDecisionAndPreservesSequence() throws Exception {
        try (var broker = new DefaultBroker(directory, 1024)) {
            long old = initialize(broker);
            broker.execute(new BrokerRequest(1, Command.JOIN_MANAGED_GROUP, "input", 0, 0, 1024,
                    null, 0, "group", "z-member"));
            assertEquals(ErrorCode.STALE_GENERATION, assertThrows(BrokerException.class,
                    () -> broker.execute(transaction(old))).errorCode());
            var assignment = broker.execute(request(Command.GROUP_ASSIGNMENT, "input", 0)).assignment();
            broker.execute(request(Command.FETCH, "input", 0).withGeneration(assignment.generation()));
            verifyCommitted(broker, assignment.generation());
        }
    }

    public static final class CrashWriter {
        public static void main(String[] args) throws Exception {
            var broker = new DefaultBroker(Path.of(args[0]), 1024);
            long generation = initialize(broker);
            var target = DefaultBroker.TransactionStage.valueOf(args[1]);
            broker.transactionFault(stage -> { if (stage == target) Runtime.getRuntime().halt(23); });
            broker.execute(transaction(generation));
            throw new AssertionError("crash boundary was not reached");
        }
    }
}
