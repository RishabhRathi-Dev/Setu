package io.setu.storage.log;

import net.openhft.chronicle.bytes.SyncMode;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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
        Files.write(path, new byte[]{1, 2, 3});
        try (var sync = new ChronicleFileSync(path)) {
            sync.force();
            Files.write(path, new byte[]{4, 5}, java.nio.file.StandardOpenOption.APPEND);
            sync.force();
        }
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, Files.readAllBytes(path));
        Files.delete(path);
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
