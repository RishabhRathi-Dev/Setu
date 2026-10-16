package io.setu.storage.log;

import net.openhft.chronicle.bytes.SyncMode;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ChronicleMessageLogTests {
    @TempDir Path directory;
    private static final long BLOCK_BYTES = 64L << 20;

    @Test void preservesDenseOffsetsPayloadBudgetsAndImmutableSnapshotsAfterReopen() throws Exception {
        var expected = new ArrayList<byte[]>();
        for (int i = 0; i < 2500; i++) {
            byte[] value = new byte[1024];
            java.nio.ByteBuffer.wrap(value).putInt(i);
            expected.add(value);
        }
        List<LogRecord> snapshot;
        try (var log = new ChronicleMessageLog(directory, 2 * 1024 * 1024, BLOCK_BYTES)) {
            assertEquals(0, log.appendBatch(expected));
            snapshot = log.read(17, 2200 * 1024);
            assertEquals(2200, snapshot.size());
            snapshot.getFirst().value()[0] = 99;
            assertEquals(2500, log.append(new byte[]{7}));
            assertEquals(2500, log.read(2500, 1).getFirst().offset());
            assertTrue(log.read(0, 1023).isEmpty());
            assertThrows(IOException.class, () -> log.read(0, 1024, bytes -> { throw new IOException("quota"); }));
        }
        for (int i = 0; i < snapshot.size(); i++) {
            assertEquals(i + 17, snapshot.get(i).offset());
            assertArrayEquals(expected.get(i + 17), snapshot.get(i).value());
        }
        try (var log = new ChronicleMessageLog(directory, 2 * 1024 * 1024, BLOCK_BYTES)) {
            assertEquals(2501, log.nextOffset());
            assertArrayEquals(expected.get(1537), log.read(1537, 1024).getFirst().value());
            assertEquals(2501, log.append(new byte[0]));
        }
        try (var files = Files.list(directory)) { assertTrue(files.anyMatch(p -> p.toString().endsWith(".cq4"))); }
    }

    @Test void rejectsWholeInvalidBatchesAndBoundsEmptyRecordFetches() throws Exception {
        try (var log = new ChronicleMessageLog(directory, 1, BLOCK_BYTES)) {
            assertThrows(IOException.class, () -> log.appendBatch(List.of(new byte[0], new byte[2])));
            assertEquals(0, log.nextOffset());
            log.appendBatch(Collections.nCopies(10_001, new byte[0]));
            assertEquals(10_000, log.read(0, 1).size());
            assertEquals(10_000, log.read(10_000, 1).getFirst().offset());
            assertTrue(log.read(0, 0).isEmpty());
        }
    }

    @Test void sequentialFetchesCanRewindResumePartialDocumentsAndFollowNewAppends() throws Exception {
        try (var log = new ChronicleMessageLog(directory, 4, BLOCK_BYTES)) {
            // Cross Chronicle's sparse-index boundaries, reading immediately after each append.
            for (int batch = 0; batch < 40; batch++) {
                int first = batch * 3;
                log.appendBatch(List.of(integerBytes(first), integerBytes(first + 1), integerBytes(first + 2)));
                assertIntegerRecords(log.read(first, 12), first, 3);
                assertTrue(log.read(first + 3, 12).isEmpty());
            }
            assertIntegerRecords(log.read(0, 4), 0, 1);
            assertIntegerRecords(log.read(1, 8), 1, 2);
            assertIntegerRecords(log.read(3, 12), 3, 3);
            assertIntegerRecords(log.read(13, 80), 13, 20);
            assertIntegerRecords(log.read(9, 12), 9, 3);
            assertIntegerRecords(log.read(117, 12), 117, 3);
        }
        try (var log = new ChronicleMessageLog(directory, 4, BLOCK_BYTES)) {
            assertEquals(120, log.nextOffset());
            assertIntegerRecords(log.read(0, 480), 0, 120);
            assertEquals(120, log.append(integerBytes(120)));
            assertIntegerRecords(log.read(120, 4), 120, 1);
        }
    }

    private static byte[] integerBytes(int value) {
        return java.nio.ByteBuffer.allocate(4).putInt(value).array();
    }

    private static void assertIntegerRecords(List<LogRecord> records, int first, int count) {
        assertEquals(count, records.size());
        for (int i = 0; i < count; i++) {
            assertEquals(first + i, records.get(i).offset());
            assertEquals(first + i, java.nio.ByteBuffer.wrap(records.get(i).value()).getInt());
        }
    }

    @Test void forceDoesNotBlockCommittedReadsOrPublishUnforcedDocumentsAndCloseWaits() throws Exception {
        var pause = new AtomicBoolean();
        var forcing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var log = new ChronicleMessageLog(directory, 4, BLOCK_BYTES, RollCycles.FAST_DAILY,
                System::currentTimeMillis, path -> new ChronicleFileSync(path) {
                    @Override void force() throws IOException {
                        if (pause.compareAndSet(true, false)) {
                            forcing.countDown();
                            try {
                                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("test force timed out");
                            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                        }
                        super.force();
                    }
                }); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            log.appendBatch(List.of(integerBytes(0), integerBytes(1), integerBytes(2)));
            pause.set(true);
            var append = workers.submit(() -> log.appendBatch(List.of(integerBytes(3), integerBytes(4), integerBytes(5))));
            try {
                assertTrue(forcing.await(5, TimeUnit.SECONDS));
                assertEquals(3L, workers.submit(log::nextOffset).get(2, TimeUnit.SECONDS));
                assertIntegerRecords(workers.submit(() -> log.read(0, 24)).get(2, TimeUnit.SECONDS), 0, 3);
                assertTrue(workers.submit(() -> log.read(3, 24)).get(2, TimeUnit.SECONDS).isEmpty());
                assertFalse(append.isDone(), "an unforced append must not be acknowledged");
                var closingStarted = new CountDownLatch(1);
                var close = workers.submit(() -> { closingStarted.countDown(); log.close(); return null; });
                assertTrue(closingStarted.await(2, TimeUnit.SECONDS));
                assertFalse(close.isDone(), "close must wait for the active force");
                release.countDown();
                assertEquals(3L, append.get(5, TimeUnit.SECONDS));
                close.get(5, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        try (var reopened = new ChronicleMessageLog(directory, 4, BLOCK_BYTES)) {
            assertIntegerRecords(reopened.read(0, 24), 0, 6);
        }
    }

    @Test void failedForceLeavesTheCommittedBoundaryUnchangedAndRejectsFurtherAccess() throws Exception {
        var fail = new AtomicBoolean();
        try (var log = new ChronicleMessageLog(directory, 4, BLOCK_BYTES, RollCycles.FAST_DAILY,
                System::currentTimeMillis, path -> new ChronicleFileSync(path) {
                    @Override void force() throws IOException {
                        if (fail.get()) throw new IOException("injected force failure");
                        super.force();
                    }
                })) {
            log.append(integerBytes(0));
            fail.set(true);
            assertThrows(IOException.class, () -> log.append(integerBytes(1)));
            assertEquals(1, log.nextOffset());
            assertThrows(IOException.class, () -> log.read(0, 8));
            assertThrows(IOException.class, () -> log.append(integerBytes(2)));
        }
    }

    @Test void offsetsContinueAcrossChronicleRollCyclesAndRestart() throws Exception {
        var clock = new AtomicLong(1_800_000_000_000L);
        try (var log = new ChronicleMessageLog(directory, 16, BLOCK_BYTES, RollCycles.FAST_DAILY, clock::get)) {
            assertEquals(0, log.appendBatch(List.of(new byte[]{1}, new byte[]{2})));
            clock.addAndGet(86_400_000L);
            assertEquals(2, log.append(new byte[]{3}));
            assertEquals(List.of(0L, 1L, 2L), log.read(0, 16).stream().map(LogRecord::offset).toList());
            assertArrayEquals(new byte[]{2}, log.read(1, 1).getFirst().value());
        }
        try (var files = Files.list(directory)) { assertEquals(2, files.filter(p -> p.toString().endsWith(".cq4")).count()); }
        try (var log = new ChronicleMessageLog(directory, 16, BLOCK_BYTES, RollCycles.FAST_DAILY, clock::get)) {
            assertEquals(3, log.nextOffset());
            assertEquals(3, log.append(new byte[]{4}));
            assertArrayEquals(new byte[]{3}, log.read(2, 1).getFirst().value());
        }
    }

    @Test void incompleteChronicleDocumentDoesNotAdvanceRecoveredSetuOffsets() throws Exception {
        try (var log = new ChronicleMessageLog(directory, 16, BLOCK_BYTES)) { log.append(new byte[]{1}); }
        try (var queue = SingleChronicleQueueBuilder.binary(directory).syncMode(SyncMode.NONE).build();
             var appender = queue.createAppender(); var document = appender.writingDocument()) {
            document.wire().bytes().writeLong(42);
            document.rollbackOnClose();
        }
        try (var log = new ChronicleMessageLog(directory, 16, BLOCK_BYTES)) {
            assertEquals(1, log.nextOffset());
            assertEquals(1, log.append(new byte[]{2}));
        }
    }

    @Test void malformedCommittedDocumentFailsOpenWithoutDiscardingData() throws Exception {
        try (var log = new ChronicleMessageLog(directory, 16, BLOCK_BYTES)) { log.append(new byte[]{1}); }
        try (var queue = SingleChronicleQueueBuilder.binary(directory).build();
             var appender = queue.createAppender(); var document = appender.writingDocument()) {
            document.wire().bytes().writeLong(42);
        }
        assertThrows(IOException.class, () -> new ChronicleMessageLog(directory, 16, BLOCK_BYTES));
    }

    @Test void legacyFilesAreNeverSilentlyIgnoredOrModified() throws Exception {
        Path legacy = directory.resolve("000000000000.log");
        byte[] original = new byte[]{0, 0, 0, 1, 7};
        Files.write(legacy, original);
        var failure = assertThrows(IOException.class, () -> new ChronicleMessageLog(directory, 16, BLOCK_BYTES));
        assertTrue(failure.getMessage().contains("offline migration"));
        assertArrayEquals(original, Files.readAllBytes(legacy));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void jdkForceCoversMappedGrowthAndReleasesFilesForImmediateDeletion() throws Exception {
        Path path = directory.resolve("force.bin");
        Files.createFile(path);
        try (var sync = new ChronicleFileSync(path);
             var writer = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var arena = Arena.ofConfined()) {
            sync.force(); // empty files still take the durability barrier
            var first = writer.map(FileChannel.MapMode.READ_WRITE, 0, 4096, arena);
            first.set(ValueLayout.JAVA_BYTE, 0, (byte) 1);
            first.set(ValueLayout.JAVA_BYTE, 4095, (byte) 2);
            sync.force();
            // Grow through a different FD while the original mapping remains live,
            // and dirty an old index/header page as well as new payload pages.
            var grown = writer.map(FileChannel.MapMode.READ_WRITE, 4096, 4096, arena);
            first.set(ValueLayout.JAVA_BYTE, 0, (byte) 3);
            grown.set(ValueLayout.JAVA_BYTE, 0, (byte) 4);
            grown.set(ValueLayout.JAVA_BYTE, 4095, (byte) 5);
            sync.force();
        }
        byte[] expected = new byte[8192];
        expected[0] = 3;
        expected[4095] = 2;
        expected[4096] = 4;
        expected[8191] = 5;
        assertArrayEquals(expected, Files.readAllBytes(path));
        Files.delete(path);
    }

    @Test void forceAfterCloseReportsTheDurabilityFailure() throws Exception {
        Path path = Files.createFile(directory.resolve("closed-force.bin"));
        var sync = new ChronicleFileSync(path);
        sync.close();
        assertThrows(IOException.class, sync::force);
    }

    @Test void acknowledgedMappedWritesRecoverAfterAbruptExitAcrossGrowthAndRoll() throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var command = new ArrayList<String>();
        command.add(javaExecutable);
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                AbruptWriter.class.getName(), directory.toString()));
        Path output = directory.resolve("writer.txt");
        Process writer = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(writer.waitFor(30, TimeUnit.SECONDS), "abrupt writer timed out");
            assertEquals(0, writer.exitValue(), () -> {
                try { return Files.readString(output); }
                catch (IOException failure) { return failure.toString(); }
            });
        } finally {
            if (writer.isAlive()) writer.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
        try (var log = new ChronicleMessageLog(directory, 1024 * 1024, BLOCK_BYTES)) {
            assertEquals(70, log.nextOffset());
            for (int i = 0; i < 70; i++) {
                var record = log.read(i, 1024 * 1024).getFirst();
                assertEquals(i, record.offset());
                assertEquals(i, java.nio.ByteBuffer.wrap(record.value()).getInt());
                assertEquals((byte) i, record.value()[1024 * 1024 - 1]);
            }
        }
    }

    public static final class AbruptWriter {
        public static void main(String[] args) throws Exception {
            var clock = new AtomicLong(1_800_000_000_000L);
            var log = new ChronicleMessageLog(Path.of(args[0]), 1024 * 1024, BLOCK_BYTES,
                    RollCycles.FAST_DAILY, clock::get);
            byte[] value = new byte[1024 * 1024];
            for (int i = 0; i < 70; i++) {
                if (i == 66) clock.addAndGet(86_400_000L);
                java.nio.ByteBuffer.wrap(value).putInt(i);
                value[value.length - 1] = (byte) i;
                if (log.append(value) != i) throw new AssertionError("wrong append offset");
            }
            // No close, unmap, shutdown hooks or background resource release may
            // supply a durability barrier missing from an acknowledged append.
            Runtime.getRuntime().halt(0);
        }
    }

    @Test void partitionOwnershipExcludesConcurrentBrokersAndReleasesOnClose() throws Exception {
        try (var first = new ChronicleMessageLog(directory, 16, BLOCK_BYTES)) {
            var error = assertThrows(IOException.class, () -> new ChronicleMessageLog(directory, 16, BLOCK_BYTES));
            assertTrue(error.getMessage().contains("already open"));
            assertEquals(0, first.append(new byte[]{1}));
        }
        try (var reopened = new ChronicleMessageLog(directory, 16, BLOCK_BYTES)) { assertEquals(1, reopened.nextOffset()); }
    }

    @Test void mappedBlockGrowthSurvivesGarbageCollectionAndReopen() throws Exception {
        byte[] payload = new byte[1024 * 1024];
        try (var log = new ChronicleMessageLog(directory, payload.length, BLOCK_BYTES)) {
            for (int i = 0; i < 160; i++) {
                java.nio.ByteBuffer.wrap(payload).putInt(i);
                assertEquals(i, log.append(payload));
                if (i % 16 == 0) System.gc();
            }
        }
        try (var log = new ChronicleMessageLog(directory, payload.length, BLOCK_BYTES)) {
            assertEquals(160, log.nextOffset());
            for (int i : new int[]{0, 63, 64, 127, 159})
                assertEquals(i, java.nio.ByteBuffer.wrap(log.read(i, payload.length).getFirst().value()).getInt());
        }
    }
}
