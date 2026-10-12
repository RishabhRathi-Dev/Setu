package io.setu.storage.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class LockFreeGroupCommitTests {
    @Test void sharedFrameSlicesCannotBypassQueueByteAdmission() throws Exception {
        var frame = MessageValue.Frame.readFrom(new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(new byte[8192])), 8192);
        try (var log = new GroupCommitMessageLog(new InMemoryMessageLog(1), 1024, 8, 0)) {
            assertThrows(LogCapacityExceededException.class,
                    () -> log.appendValues(List.of(frame.valueAt(0, 1))));
            assertEquals(0, log.outstandingBytes());
            assertEquals(0, log.outstandingRequests());
        }
    }

    @Test void detachedGroupsKeepFifoBatchOffsetsAndIncludeActiveBytesInAdmission() throws Exception {
        var delegate = new GatedLog(false);
        try (var log = new GroupCommitMessageLog(delegate, 200, 8, 0);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> log.append(new byte[]{1}));
            try {
                assertTrue(delegate.entered.await(3, TimeUnit.SECONDS));
                var second = workers.submit(() -> log.appendBatch(List.of(new byte[]{2}, new byte[]{3})));
                awaitRequests(log, 2);
                var third = workers.submit(() -> log.appendBatch(List.of(new byte[]{4}, new byte[]{5}, new byte[]{6})));
                awaitRequests(log, 3);
                assertEquals(198, log.outstandingBytes());
                assertThrows(LogCapacityExceededException.class, () -> log.append(new byte[]{7}));
                assertFalse(first.isDone()); assertFalse(second.isDone()); assertFalse(third.isDone());
                delegate.flush.countDown();
                assertEquals(0, first.get(3, TimeUnit.SECONDS));
                assertEquals(1, second.get(3, TimeUnit.SECONDS));
                assertEquals(3, third.get(3, TimeUnit.SECONDS));
                assertEquals(List.of(1, 2, 3, 4, 5, 6), delegate.values);
                assertEquals(0, log.outstandingBytes());
                assertEquals(0, log.outstandingRequests());
                assertEquals(6, log.append(new byte[]{7}));
            } finally { delegate.flush.countDown(); }
        }
    }

    @Test void failedFlushFailsEveryAcceptedRequestAndReleasesCapacity() throws Exception {
        var delegate = new GatedLog(true);
        try (var log = new GroupCommitMessageLog(delegate, 8192, 64, 0);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<Future<Long>>();
            results.add(workers.submit(() -> log.append(new byte[]{0})));
            try {
                assertTrue(delegate.entered.await(3, TimeUnit.SECONDS));
                for (int i = 1; i < 32; i++) results.add(workers.submit(() -> log.append(new byte[]{1})));
                awaitRequests(log, 32);
            } finally { delegate.flush.countDown(); }
            for (var result : results) {
                var failure = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
                assertInstanceOf(IOException.class, failure.getCause());
            }
            assertEquals(0, log.outstandingBytes());
            assertEquals(0, log.outstandingRequests());
            assertThrows(IOException.class, () -> log.append(new byte[]{2}));
        } finally { delegate.flush.countDown(); }
    }

    @Test void closeDrainsAcceptedRequestsWithoutWaitingForLingerAndClosesDelegateOnce() throws Exception {
        var delegate = new GatedLog(false);
        delegate.flush.countDown();
        var log = new GroupCommitMessageLog(delegate, 8192, 64, TimeUnit.SECONDS.toNanos(30));
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> log.append(new byte[]{1}));
            awaitRequests(log, 1);
            var second = workers.submit(() -> log.append(new byte[]{2}));
            awaitRequests(log, 2);
            var closes = new ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) closes.add(workers.submit(() -> { log.close(); return null; }));
            for (var close : closes) close.get(3, TimeUnit.SECONDS);
            assertEquals(0, first.get(3, TimeUnit.SECONDS));
            assertEquals(1, second.get(3, TimeUnit.SECONDS));
            assertEquals(1, delegate.closes.get());
            assertEquals(0, log.outstandingRequests());
            assertThrows(IOException.class, () -> log.append(new byte[]{3}));
        } finally { log.close(); }
    }

    @Test void interruptedCallerStillGetsItsDurableOffsetAndRestoresInterruptStatus() throws Exception {
        var delegate = new GatedLog(false);
        var result = new AtomicReference<Long>();
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        try (var log = new GroupCommitMessageLog(delegate, 64, 1, 0)) {
            Thread caller = Thread.ofVirtual().start(() -> {
                try { result.set(log.append(new byte[]{1})); interrupted.set(Thread.currentThread().isInterrupted()); }
                catch (Throwable e) { failure.set(e); }
            });
            try {
                assertTrue(delegate.entered.await(3, TimeUnit.SECONDS));
                caller.interrupt();
                assertNull(result.get());
                assertThrows(LogCapacityExceededException.class, () -> log.append(new byte[]{2}));
            } finally { delegate.flush.countDown(); }
            caller.join(3000);
            assertFalse(caller.isAlive());
            assertNull(failure.get());
            assertEquals(0, result.get());
            assertTrue(interrupted.get());
            assertEquals(1, log.append(new byte[]{2}));
        } finally { delegate.flush.countDown(); }
    }

    @Test void racingAdmissionAndCloseCompletesAllCallersWithContiguousDurableOffsets() throws Exception {
        for (int repetition = 0; repetition < 20; repetition++) {
            var delegate = new GatedLog(false);
            delegate.flush.countDown();
            try (var log = new GroupCommitMessageLog(delegate, 8192, 64, 0);
                 var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var start = new CyclicBarrier(33);
                var results = new ArrayList<Future<Long>>();
                for (int i = 0; i < 32; i++) results.add(workers.submit(() -> {
                    start.await();
                    try { return log.append(new byte[]{1}); }
                    catch (IOException closed) { return -1L; }
                }));
                var close = workers.submit(() -> { start.await(); log.close(); return null; });
                var accepted = new ArrayList<Long>();
                for (var result : results) { long offset = result.get(3, TimeUnit.SECONDS); if (offset >= 0) accepted.add(offset); }
                close.get(3, TimeUnit.SECONDS);
                accepted.sort(Long::compare);
                for (int i = 0; i < accepted.size(); i++) assertEquals((long) i, accepted.get(i));
                assertEquals(accepted.size(), delegate.values.size());
                assertEquals(0, log.outstandingBytes());
                assertEquals(1, delegate.closes.get());
            }
        }
    }

    private static void awaitRequests(GroupCommitMessageLog log, int requests) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (log.outstandingRequests() != requests && System.nanoTime() < deadline) LockSupport.parkNanos(100_000);
        assertEquals(requests, log.outstandingRequests(), "request was not admitted");
    }

    private static final class GatedLog implements MessageLog {
        final CountDownLatch entered = new CountDownLatch(1), flush = new CountDownLatch(1);
        final AtomicInteger closes = new AtomicInteger();
        final List<Integer> values = new ArrayList<>();
        final boolean fail;
        GatedLog(boolean fail) { this.fail = fail; }
        public long append(byte[] value) { throw new UnsupportedOperationException(); }
        public long appendValues(List<MessageValue> batch) throws IOException {
            entered.countDown();
            try { if (!flush.await(5, TimeUnit.SECONDS)) throw new IOException("test flush timed out"); }
            catch (InterruptedException e) { throw new IOException(e); }
            if (fail) throw new IOException("injected flush failure");
            long first = values.size();
            for (MessageValue value : batch) values.add((int) value.copy()[0]);
            return first;
        }
        public List<LogRecord> read(long offset, int bytes) { return List.of(); }
        public long nextOffset() { return values.size(); }
        public void close() { closes.incrementAndGet(); }
    }
}
