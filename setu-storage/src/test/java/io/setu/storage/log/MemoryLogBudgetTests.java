package io.setu.storage.log;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MemoryLogBudgetTests {
    @Test
    void sharesCapacityAndRejectsWholeBatchesUntilReclamationOrClose() throws Exception {
        var budget = new MemoryLogBudget(32 * 1024);
        var values = Collections.nCopies(32, new byte[256]);
        try (var first = new InMemoryMessageLog(256, budget);
             var second = new InMemoryMessageLog(256, budget)) {
            assertEquals(0, first.appendBatch(values));
            long used = budget.usedBytes();
            assertThrows(LogCapacityExceededException.class, () -> second.appendBatch(values));
            assertEquals(0, second.nextOffset());
            assertEquals(used, budget.usedBytes());
            first.discardBefore(16);
            assertTrue(budget.usedBytes() < used);
            assertEquals(0, second.appendBatch(values));
            first.discardBefore(0); // Older acknowledgements cannot release capacity twice.
            assertEquals(16, first.firstOffset());
            first.close();
            first.close();
            assertEquals(used, budget.usedBytes());
            assertEquals(32, second.read(0, 8192).size());
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test
    void chargesEmptyRecordsAndChunkStorageRatherThanOnlyPayloadBytes() throws Exception {
        var budget = new MemoryLogBudget(9000);
        try (var log = new InMemoryMessageLog(0, budget)) {
            assertEquals(0, log.append(new byte[0]));
            assertTrue(budget.usedBytes() > 0);
            long used = budget.usedBytes();
            assertThrows(LogCapacityExceededException.class,
                    () -> log.appendBatch(Collections.nCopies(1000, new byte[0])));
            assertEquals(1, log.nextOffset());
            assertEquals(used, budget.usedBytes());
            log.discardBefore(1);
            assertEquals(0, budget.usedBytes());
            assertEquals(1, log.append(new byte[0]));
        }
        assertEquals(0, budget.usedBytes());
        assertThrows(IllegalArgumentException.class, () -> new MemoryLogBudget(-1));
    }

    @Test
    void concurrentPartitionAppendsCannotOversubscribeTheSharedBudget() throws Exception {
        var budget = new MemoryLogBudget(64 * 1024);
        var logs = new ArrayList<InMemoryMessageLog>();
        var succeeded = new AtomicInteger();
        var rejected = new AtomicInteger();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(16)) {
            var results = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 16; i++) {
                var log = new InMemoryMessageLog(256, budget);
                logs.add(log);
                results.add(workers.submit(() -> {
                    start.await();
                    for (int j = 0; j < 100; j++) {
                        try { log.append(new byte[256]); succeeded.incrementAndGet(); }
                        catch (LogCapacityExceededException expected) { rejected.incrementAndGet(); }
                        assertTrue(budget.usedBytes() <= 64 * 1024);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var result : results) result.get(10, TimeUnit.SECONDS);
            assertTrue(succeeded.get() > 0);
            assertTrue(rejected.get() > 0);
            assertEquals(succeeded.get(), logs.stream().mapToLong(InMemoryMessageLog::nextOffset).sum());
        } finally {
            for (var log : logs) log.close();
        }
        assertEquals(0, budget.usedBytes());
    }
}
