package io.setu.storage.log;

import net.openhft.chronicle.queue.RollCycles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ChronicleRetentionTests {
    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final long BLOCK = 64L << 20;
    @TempDir Path directory;
    private final AtomicLong clock = new AtomicLong(20_000 * DAY);

    private ChronicleMessageLog open() throws IOException {
        return new ChronicleMessageLog(directory, 1024, BLOCK, RollCycles.FAST_DAILY, clock::get);
    }

    private List<Path> cycles() throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.toString().endsWith(".cq4")).sorted().toList();
        }
    }

    @Test void expiresOnlyCompleteCyclesAtTheAgeBoundaryAndPreservesOffsets() throws Exception {
        try (var log = open()) {
            log.appendBatch(List.of(new byte[]{0}, new byte[]{1}));
            clock.addAndGet(DAY);
            log.append(new byte[]{2});
            clock.addAndGet(DAY - 1);
            log.discardExpiredFiles(DAY);
            assertEquals(0, log.firstOffset());
            assertEquals(2, cycles().size());
            clock.incrementAndGet();
            log.discardExpiredFiles(DAY);
            assertEquals(2, log.firstOffset());
            assertEquals(3, log.nextOffset());
            assertEquals(1, cycles().size());
            assertEquals(2, log.read(0, 1024).getFirst().offset());
            assertArrayEquals(new byte[]{2}, log.read(2, 1024).getFirst().value());
            assertEquals(3, log.append(new byte[]{3}));
        }
        try (var log = open()) {
            assertEquals(2, log.firstOffset());
            assertEquals(4, log.nextOffset());
            assertEquals(List.of(2L, 3L), log.read(2, 1024).stream().map(LogRecord::offset).toList());
            assertEquals(4, log.append(new byte[]{4}));
        }
    }

    @Test void disabledRetentionAndNewestCycleKeepHistoryEvenWhileIdle() throws Exception {
        try (var log = open()) {
            log.discardExpiredFiles(DAY); // empty queue
            log.append(new byte[]{0});
            clock.addAndGet(DAY);
            log.append(new byte[]{1});
            clock.addAndGet(10 * DAY);
            log.discardExpiredFiles(0);
            assertEquals(2, cycles().size());
            assertEquals(0, log.firstOffset());
            log.discardExpiredFiles(DAY);
            assertEquals(1, cycles().size());
            assertEquals(1, log.firstOffset());
            log.discardExpiredFiles(DAY);
            assertEquals(1, log.read(1, 1024).size());
            assertEquals(2, log.append(new byte[]{2}));
            log.discardExpiredFiles(DAY);
            assertEquals(2, log.firstOffset());
            assertEquals(1, cycles().size());
        }
    }

    @Test void checkpointIgnoresAndRetriesFilesLeftByInterruptedDeletion() throws Exception {
        Path backup = directory.resolve("old-cycle.backup");
        Path old;
        try (var log = open()) {
            log.append(new byte[]{0});
            clock.addAndGet(DAY);
            log.append(new byte[]{1});
            clock.addAndGet(DAY);
            log.append(new byte[]{2});
            old = cycles().getFirst();
            Files.copy(old, backup);
            clock.incrementAndGet();
            log.discardExpiredFiles(1);
            assertEquals(2, log.firstOffset());
        }
        Files.copy(backup, old); // checkpoint committed, obsolete unlink did not survive
        try (var log = open()) {
            assertEquals(2, log.firstOffset());
            assertEquals(3, log.nextOffset());
            assertEquals(1, log.read(0, 1024).size());
            log.discardExpiredFiles(1);
            assertFalse(Files.exists(old));
            assertEquals(3, log.append(new byte[]{3}));
        }
    }

    @Test void missingRetainedFileAndCorruptCheckpointFailWithoutResettingOffsets() throws Exception {
        try (var log = open()) {
            log.append(new byte[]{0});
            clock.addAndGet(DAY);
            log.append(new byte[]{1});
            clock.addAndGet(DAY);
            log.discardExpiredFiles(1);
        }
        Path retained = cycles().getFirst();
        Path backup = directory.resolve("retained.backup");
        Files.move(retained, backup);
        assertThrows(IOException.class, this::open);
        Files.move(backup, retained);
        byte[] checkpoint = Files.readAllBytes(directory.resolve(".setu-retention"));
        checkpoint[15] ^= 1;
        Files.write(directory.resolve(".setu-retention"), checkpoint);
        assertThrows(IOException.class, this::open);
        assertTrue(Files.exists(retained));
    }

    @Test void groupCommitReadsAndWritesSerializeWithRetention() throws Exception {
        try (var log = new GroupCommitMessageLog(open(), 1 << 20, 100, 0);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            log.append(new byte[]{0});
            clock.addAndGet(DAY);
            log.append(new byte[]{1});
            clock.addAndGet(DAY);
            var writes = workers.submit(() -> {
                for (int i = 0; i < 20; i++) log.append(new byte[]{2});
                return log.nextOffset();
            });
            var reads = workers.submit(() -> {
                for (int i = 0; i < 20; i++) assertFalse(log.read(0, 1024).isEmpty());
                return true;
            });
            log.discardExpiredFiles(1);
            assertEquals(22, writes.get(10, TimeUnit.SECONDS));
            assertTrue(reads.get(10, TimeUnit.SECONDS));
            clock.incrementAndGet();
            log.discardExpiredFiles(1);
            assertEquals(2, log.firstOffset());
            assertEquals(20, log.read(2, 1024).size());
        }
    }

    @Test void checkpointPublicationFailurePreservesTheReadablePrefix() throws Exception {
        try (var log = open()) {
            log.append(new byte[]{0});
            clock.addAndGet(DAY);
            log.append(new byte[]{1});
            clock.addAndGet(DAY);
            Path blocked = Files.createDirectory(directory.resolve(".setu-retention"));
            Path child = Files.write(blocked.resolve("block-replacement"), new byte[]{1});
            assertThrows(IOException.class, () -> log.discardExpiredFiles(1));
            assertEquals(0, log.firstOffset());
            assertEquals(2, cycles().size());
            assertEquals(2, log.read(0, 1024).size());
            Files.delete(child);
            Files.delete(blocked);
            log.discardExpiredFiles(1);
            assertEquals(1, log.firstOffset());
            assertEquals(2, log.append(new byte[]{2}));
        }
    }
}
