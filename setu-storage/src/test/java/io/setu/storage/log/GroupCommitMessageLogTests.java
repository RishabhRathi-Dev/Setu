package io.setu.storage.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GroupCommitMessageLogTests {
    @TempDir Path directory;
    @Test void failedFlushFailsActiveAndQueuedRequestsWithoutAcknowledgingThem() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        MessageLog failed = new MessageLog() {
            public long append(byte[] value) { throw new UnsupportedOperationException(); }
            public long appendValues(List<MessageValue> values) throws java.io.IOException {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { throw new java.io.IOException(e); }
                throw new java.io.IOException("injected flush failure");
            }
            public List<LogRecord> read(long offset, int bytes) { return List.of(); }
            public long nextOffset() { return 0; }
            public void close() { }
        };
        try (var log = new GroupCommitMessageLog(failed, 256, 2, 0);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> log.append(new byte[]{1}));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var second = workers.submit(() -> log.append(new byte[]{2}));
            release.countDown();
            assertThrows(ExecutionException.class, () -> first.get(3, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> second.get(3, TimeUnit.SECONDS));
            assertThrows(java.io.IOException.class, () -> log.append(new byte[]{3}));
        } finally { release.countDown(); }
    }
    @Test void concurrentRequestsShareAFlushAndRemainRecoverable() throws Exception {
        var calls = new AtomicInteger();
        var file = new SegmentedFileMessageLog(directory, 64, 1024);
        MessageLog counted = new MessageLog() {
            public long append(byte[] value) throws java.io.IOException { return file.append(value); }
            public long appendValues(List<MessageValue> values) throws java.io.IOException { calls.incrementAndGet(); return file.appendValues(values); }
            public List<LogRecord> read(long offset, int bytes) throws java.io.IOException { return file.read(offset, bytes); }
            public long nextOffset() { return file.nextOffset(); }
            public void close() throws java.io.IOException { file.close(); }
        };
        try (var log = new GroupCommitMessageLog(counted, 8192, 32, TimeUnit.MILLISECONDS.toNanos(100));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier = new CyclicBarrier(16);
            var results = new java.util.ArrayList<Future<Long>>();
            for (int i = 0; i < 16; i++) {
                int id = i;
                results.add(executor.submit(() -> { barrier.await(); return log.append(new byte[]{(byte) id}); }));
            }
            var offsets = new java.util.HashSet<Long>();
            for (var result : results) offsets.add(result.get(5, TimeUnit.SECONDS));
            assertEquals(16, offsets.size());
            assertTrue(calls.get() < 16, "concurrent appends should share durable calls");
            assertEquals(16, log.read(0, 4096).size());
        }
        try (var recovered = new SegmentedFileMessageLog(directory, 64, 1024)) {
            assertEquals(16, recovered.nextOffset());
            assertEquals(16, recovered.read(0, 4096).size());
        }
    }

    @Test void boundsActiveFlushAsWellAsQueuedBytesAndNeverAcknowledgesBeforeFlush() throws Exception {
        var entered = new CountDownLatch(1);
        var flush = new CountDownLatch(1);
        MessageLog blocked = new MessageLog() {
            public long append(byte[] value) { throw new UnsupportedOperationException(); }
            public long appendValues(List<MessageValue> values) throws java.io.IOException {
                entered.countDown();
                try { if (!flush.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("timeout"); }
                catch (InterruptedException e) { throw new java.io.IOException(e); }
                return 0;
            }
            public List<LogRecord> read(long offset, int bytes) { return List.of(); }
            public long nextOffset() { return 0; }
            public void close() { }
        };
        try (var log = new GroupCommitMessageLog(blocked, 64, 1, 0);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Long> result = executor.submit(() -> log.append(new byte[]{1}));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(result.isDone());
                assertThrows(LogCapacityExceededException.class, () -> log.append(new byte[]{2}));
            } finally { flush.countDown(); }
            assertEquals(0L, result.get(5, TimeUnit.SECONDS));
        }
    }
}
