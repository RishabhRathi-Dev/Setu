package io.setu.benchmark.openhft;

import io.setu.storage.log.FileMessageLog;
import io.setu.storage.log.MessageValue;
import net.openhft.chronicle.bytes.SyncMode;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in storage experiment. Neither engine is allowed to acknowledge before synchronous flush. */
class ChronicleQueueComparisonTests {
    private static final int RECORD_BYTES = 1024;
    private static final int BATCH = 256;
    private static final int WARMUP_BATCHES = 64;
    private static final int BATCHES = 1024;
    @TempDir Path temporary;

    @Test @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void compareSynchronousBatchAppendsAndVerifyEveryRecordAfterReopen() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux"),
                "Durable comparison requires Linux: OpenHFT WinJNRPosixAPI.msync is a no-op on Windows");
        assertFalse(net.openhft.posix.PosixAPI.posix().getClass().getName().contains("NoOp"),
                "A no-op native sync implementation cannot establish durability");
        var raw = new ArrayList<byte[]>();
        for (int record = 0; record < BATCH; record++) {
            byte[] value = new byte[RECORD_BYTES];
            new java.util.SplittableRandom(record).nextBytes(value);
            raw.add(value);
        }
        List<MessageValue> immutable = raw.stream().map(MessageValue::copyOf).toList();
        System.out.printf(Locale.ROOT,
                "OPENHFT_CONFIG java=%s os=%s record_bytes=%d batch_records=%d warmup_batches=%d measured_batches=%d repeats=3 durability=synchronous_flush_each_batch scope=storage_append_only%n",
                System.getProperty("java.version"), System.getProperty("os.name"), RECORD_BYTES, BATCH, WARMUP_BATCHES, BATCHES);
        for (int repeat = 1; repeat <= 3; repeat++) {
            // Alternate order to reduce systematic cache/JIT/order bias.
            if ((repeat & 1) == 1) { file(repeat, immutable, raw); chronicle(repeat, raw); }
            else { chronicle(repeat, raw); file(repeat, immutable, raw); }
        }
    }

    private void file(int repeat, List<MessageValue> values, List<byte[]> raw) throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("file-" + repeat));
        Path path = directory.resolve("data.log");
        try {
            Histogram latency = new Histogram(3);
            long elapsed;
            try (var log = new FileMessageLog(path, RECORD_BYTES)) {
                for (int i = 0; i < WARMUP_BATCHES; i++) log.appendValues(values);
                long started = System.nanoTime();
                for (int i = 0; i < BATCHES; i++) {
                    long before = System.nanoTime();
                    log.appendValues(values);
                    latency.recordValue(System.nanoTime() - before);
                }
                elapsed = System.nanoTime() - started;
            }
            long recoveryStarted = System.nanoTime();
            try (var log = new FileMessageLog(path, RECORD_BYTES)) {
                long recoveryNanos = System.nanoTime() - recoveryStarted;
                long count = (WARMUP_BATCHES + BATCHES) * (long) BATCH;
                assertEquals(count, log.nextOffset());
                for (long offset = 0; offset < count; offset += BATCH) {
                    var records = log.read(offset, BATCH * RECORD_BYTES);
                    assertEquals(BATCH, records.size());
                    for (int i = 0; i < BATCH; i++) {
                        assertEquals(offset + i, records.get(i).offset());
                        assertArrayEquals(raw.get(i), records.get(i).value());
                    }
                }
                report("SETU_FILE", repeat, elapsed, latency, recoveryNanos);
            }
        } finally { deleteOwnDirectory(directory); }
    }

    private void chronicle(int repeat, List<byte[]> values) throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("chronicle-" + repeat));
        try {
            Histogram latency = new Histogram(3);
            long elapsed;
            try (var queue = SingleChronicleQueueBuilder.binary(directory).blockSize(64L << 20).syncMode(SyncMode.SYNC).build();
                 var appender = queue.createAppender()) {
                long started = 0;
                for (int i = 0; i < WARMUP_BATCHES + BATCHES; i++) {
                    if (i == WARMUP_BATCHES) started = System.nanoTime();
                    long before = System.nanoTime();
                    try (var document = appender.writingDocument()) {
                        var bytes = document.wire().bytes();
                        bytes.writeLong(i * (long) BATCH).writeInt(BATCH);
                        for (byte[] value : values) bytes.writeInt(value.length).write(value);
                    }
                    appender.sync();
                    if (i >= WARMUP_BATCHES) latency.recordValue(System.nanoTime() - before);
                }
                elapsed = System.nanoTime() - started;
            }
            long recoveryStarted = System.nanoTime();
            try (var queue = SingleChronicleQueueBuilder.binary(directory).blockSize(64L << 20).syncMode(SyncMode.SYNC).build();
                 var tailer = queue.createTailer()) {
                long recoveryNanos = System.nanoTime() - recoveryStarted;
                byte[] value = new byte[RECORD_BYTES];
                for (int batch = 0; batch < WARMUP_BATCHES + BATCHES; batch++) {
                    try (var document = tailer.readingDocument()) {
                        assertTrue(document.isPresent());
                        var bytes = document.wire().bytes();
                        assertEquals(batch * (long) BATCH, bytes.readLong());
                        assertEquals(BATCH, bytes.readInt());
                        for (int i = 0; i < BATCH; i++) {
                            assertEquals(RECORD_BYTES, bytes.readInt());
                            assertEquals(RECORD_BYTES, bytes.read(value));
                            assertArrayEquals(values.get(i), value);
                        }
                        assertEquals(0, bytes.readRemaining());
                    }
                }
                try (var document = tailer.readingDocument()) { assertFalse(document.isPresent()); }
                report("CHRONICLE_QUEUE", repeat, elapsed, latency, recoveryNanos);
            }
        } finally { deleteOwnDirectory(directory); }
    }

    private static void report(String engine, int repeat, long elapsed, Histogram latency, long recoveryNanos) {
        System.out.printf(Locale.ROOT,
                "OPENHFT_COMPARISON engine=%s repeat=%d records=%d duration_s=%.6f records_per_s=%.3f batch_p50_ms=%.6f batch_p99_ms=%.6f reopen_ms=%.3f verified=true qualified=false%n",
                engine, repeat, BATCH * BATCHES, elapsed / 1e9, BATCH * BATCHES / (elapsed / 1e9),
                latency.getValueAtPercentile(50) / 1e6, latency.getValueAtPercentile(99) / 1e6, recoveryNanos / 1e6);
    }

    private static void deleteOwnDirectory(Path directory) throws Exception {
        net.openhft.chronicle.core.io.BackgroundResourceReleaser.releasePendingResources();
        // Only the fresh per-engine directory created by this test is removed.
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
