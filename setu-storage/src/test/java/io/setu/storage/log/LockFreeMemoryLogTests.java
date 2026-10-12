package io.setu.storage.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class LockFreeMemoryLogTests {
    @Test void chunkAdmissionReservesCompleteResultsAndRejectsWithoutMutatingLog() throws Exception {
        try (var log = new InMemoryMessageLog(3)) {
            log.appendBatch(Collections.nCopies(2500, new byte[]{1, 2, 3}));
            var reservations = new ArrayList<Long>();
            var records = log.read(1000, 3300, reservations::add);
            assertEquals(1100, records.size());
            assertEquals(1000, records.getFirst().offset());
            assertEquals(2099, records.getLast().offset());
            assertEquals(List.of(24L * 67 + 22, 1048L * 67 + 22, 1100L * 67 + 22), reservations);
            assertThrows(IOException.class, () -> log.read(0, 10_000, bytes -> {
                if (bytes > 100) throw new IOException("budget exhausted");
            }));
            assertEquals(2500, log.nextOffset());
            assertEquals(2500, log.read(0, 10_000).size());
        }
    }

    @Test void retainedFrameSliceCannotBypassMemoryBudget() throws Exception {
        var frame = MessageValue.Frame.readFrom(new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(new byte[64 * 1024])), 64 * 1024);
        var budget = new MemoryLogBudget(32 * 1024);
        try (var log = new InMemoryMessageLog(1, budget)) {
            assertThrows(IOException.class, () -> log.appendValues(List.of(frame.valueAt(0, 1))));
            assertEquals(0, budget.usedBytes());
            assertEquals(0, log.nextOffset());
        }
    }

    @Test void stalledFetchDoesNotBlockAppendReclamationOrCloseAndKeepsItsSnapshot() throws Exception {
        var budget = new MemoryLogBudget(64 * 1024);
        var log = new InMemoryMessageLog(16, budget);
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            log.appendBatch(List.of(new byte[]{1}, new byte[]{2}, new byte[]{3}));
            var fetched = workers.submit(() -> log.read(0, 16, bytes -> {
                entered.countDown();
                try { if (!resume.await(5, TimeUnit.SECONDS)) throw new IOException("fetch stalled"); }
                catch (InterruptedException e) { throw new IOException(e); }
            }));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                workers.submit(() -> { assertEquals(3, log.append(new byte[]{4})); log.discardBefore(4); log.close(); return null; })
                        .get(3, TimeUnit.SECONDS);
                assertEquals(0, budget.usedBytes());
            } finally { resume.countDown(); }
            var snapshot = fetched.get(3, TimeUnit.SECONDS);
            assertEquals(3, snapshot.size());
            for (int i = 0; i < 3; i++) assertArrayEquals(new byte[]{(byte) (i + 1)}, snapshot.get(i).value());
            assertThrows(IOException.class, () -> log.append(new byte[]{5}));
        } finally { resume.countDown(); log.close(); }
    }

    @Test void concurrentBatchesPublishContiguousOffsetsAndCompletePayloads() throws Exception {
        int producers = 8, batches = 100, batchSize = 31;
        var budget = new MemoryLogBudget(16 * 1024 * 1024);
        try (var log = new InMemoryMessageLog(8, budget);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CyclicBarrier(producers + 1);
            var running = new AtomicBoolean(true);
            var reader = workers.submit(() -> {
                long offset = 0;
                while (running.get() || offset < log.nextOffset()) {
                    var page = log.read(offset, 8192);
                    for (LogRecord record : page) {
                        assertEquals(offset++, record.offset());
                        ByteBuffer value = ByteBuffer.wrap(record.value());
                        int id = value.getInt(), index = value.getInt();
                        assertTrue(id >= 0 && id < producers);
                        assertTrue(index >= 0 && index < batches);
                    }
                    if (page.isEmpty()) Thread.yield();
                }
                return offset;
            });
            List<Future<List<Long>>> results = new ArrayList<>();
            for (int p = 0; p < producers; p++) {
                int id = p;
                results.add(workers.submit(() -> {
                    start.await();
                    List<Long> offsets = new ArrayList<>();
                    for (int batch = 0; batch < batches; batch++) {
                        byte[] value = ByteBuffer.allocate(8).putInt(id).putInt(batch).array();
                        offsets.add(log.appendBatch(Collections.nCopies(batchSize, value)));
                    }
                    return offsets;
                }));
            }
            start.await();
            List<Long> offsets = new ArrayList<>();
            try { for (var result : results) offsets.addAll(result.get(10, TimeUnit.SECONDS)); }
            finally { running.set(false); }
            Collections.sort(offsets);
            for (int i = 0; i < offsets.size(); i++) assertEquals((long) i * batchSize, offsets.get(i));
            assertEquals((long) producers * batches * batchSize, reader.get(5, TimeUnit.SECONDS));
            for (long offset : offsets) {
                var page = log.read(offset, batchSize * 8);
                assertEquals(batchSize, page.size());
                for (LogRecord record : page) assertArrayEquals(page.getFirst().value(), record.value());
            }
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void prefixReclamationAndAppendMatchASequentialOracleAcrossManyChunks() throws Exception {
        var random = new Random(481);
        var expected = new ArrayList<byte[]>();
        try (var log = new InMemoryMessageLog(8)) {
            long first = 0;
            for (int step = 0; step < 300; step++) {
                int count = 1 + random.nextInt(2048);
                List<byte[]> batch = new ArrayList<>();
                for (int i = 0; i < count; i++) batch.add(ByteBuffer.allocate(8).putLong(expected.size() + i).array());
                assertEquals(expected.size(), log.appendBatch(batch));
                expected.addAll(batch);
                if (step % 7 == 0) first += random.nextInt((int) (expected.size() - first + 1));
                log.discardBefore(first);
                if (first == expected.size()) continue;
                long offset = first + random.nextInt((int) (expected.size() - first));
                var page = log.read(offset, 8192);
                assertEquals(Math.min(1024, expected.size() - offset), page.size());
                for (int i = 0; i < page.size(); i++) {
                    assertEquals(offset + i, page.get(i).offset());
                    assertArrayEquals(expected.get((int) offset + i), page.get(i).value());
                }
            }
            log.discardBefore(expected.size());
            assertTrue(log.read(0, 8192).isEmpty());
            assertEquals(expected.size(), log.append(new byte[8]));
        }
    }

    @Test void racingCloseAndAppendsReleaseAllReservationsWithoutPublishingAfterClose() throws Exception {
        var budget = new MemoryLogBudget(32 * 1024 * 1024);
        for (int repetition = 0; repetition < 20; repetition++) {
            var log = new InMemoryMessageLog(16, budget);
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var start = new CountDownLatch(1);
                var results = new ArrayList<Future<?>>();
                for (int i = 0; i < 8; i++) results.add(workers.submit(() -> {
                    start.await();
                    for (int j = 0; j < 100; j++) {
                        try { log.appendBatch(Collections.nCopies(128, new byte[16])); }
                        catch (IOException closed) { break; }
                    }
                    return null;
                }));
                results.add(workers.submit(() -> { start.await(); log.close(); return null; }));
                start.countDown();
                for (var result : results) result.get(5, TimeUnit.SECONDS);
                assertEquals(log.nextOffset(), log.firstOffset());
                assertEquals(0, budget.usedBytes());
            } finally { log.close(); }
        }
    }

    @Test void concurrentReclamationAndAppendsKeepOffsetsMonotonicAndReleaseTheSharedBudget() throws Exception {
        var budget = new MemoryLogBudget(32 * 1024 * 1024);
        try (var log = new InMemoryMessageLog(8, budget);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = new AtomicBoolean(true);
            var start = new CyclicBarrier(4);
            var reclaim = workers.submit(() -> {
                start.await();
                long previous = 0;
                while (running.get()) {
                    long next = log.nextOffset();
                    assertTrue(next >= previous);
                    log.discardBefore(next);
                    assertTrue(log.firstOffset() >= previous);
                    previous = next;
                    Thread.yield();
                }
                return null;
            });
            var read = workers.submit(() -> {
                start.await();
                while (running.get()) {
                    var page = log.read(0, 8192);
                    if (!page.isEmpty()) {
                        long offset = page.getFirst().offset();
                        for (LogRecord record : page) {
                            assertEquals(offset++, record.offset());
                            assertEquals(8, record.value().length);
                        }
                    }
                    Thread.yield();
                }
                return null;
            });
            var producers = new ArrayList<Future<List<Long>>>();
            for (int i = 0; i < 2; i++) producers.add(workers.submit(() -> {
                start.await();
                var offsets = new ArrayList<Long>();
                for (int j = 0; j < 400; j++) offsets.add(log.appendBatch(Collections.nCopies(127, new byte[8])));
                return offsets;
            }));
            var offsets = new ArrayList<Long>();
            try { for (var producer : producers) offsets.addAll(producer.get(10, TimeUnit.SECONDS)); }
            finally { running.set(false); }
            reclaim.get(3, TimeUnit.SECONDS);
            read.get(3, TimeUnit.SECONDS);
            offsets.sort(Long::compare);
            for (int i = 0; i < offsets.size(); i++) assertEquals((long) i * 127, offsets.get(i));
            assertEquals(800L * 127, log.nextOffset());
            log.discardBefore(log.nextOffset());
            assertEquals(0, budget.usedBytes());
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void timedRetentionRemovesOnlyExpiredRecordsWithinAPartialChunk() throws Exception {
        var budget = new MemoryLogBudget(64 * 1024);
        try (var log = new InMemoryMessageLog(16, budget, true)) {
            log.appendBatch(List.of(new byte[]{1}, new byte[]{2}));
            long cutoff = System.nanoTime();
            while (System.nanoTime() == cutoff) Thread.onSpinWait();
            log.append(new byte[]{3});
            long used = budget.usedBytes();
            log.discardExpired(cutoff + 1, 1);
            assertEquals(2, log.firstOffset());
            assertEquals(3, log.nextOffset());
            assertTrue(budget.usedBytes() < used);
            assertArrayEquals(new byte[]{3}, log.read(0, 16).getFirst().value());
        }
        assertEquals(0, budget.usedBytes());
    }
}
