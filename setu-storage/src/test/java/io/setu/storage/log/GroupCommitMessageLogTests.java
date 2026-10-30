package io.setu.storage.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GroupCommitMessageLogTests {
    @TempDir Path directory;

    @Test void disabledAdmissionLimitsRemainIndependentAndAcceptedWritesDrainOnClose() throws Exception {
        for (var options : List.of(new FileWriteQueueOptions(0, 1),
                new FileWriteQueueOptions(64, 0), FileWriteQueueOptions.unlimited())) {
            var delegate = new CallbackLog(new InMemoryMessageLog(16), false);
            var log = new GroupCommitMessageLog(delegate, options.maxBytes(), options.maxRequests(), 0);
            try {
                var first = log.appendValuesAsync(List.of(MessageValue.copyOf(new byte[4])));
                assertTrue(delegate.entered.await(3, TimeUnit.SECONDS));
                assertFalse(first.isDone(), "disabling admission must not acknowledge before the delegate flush");
                CompletableFuture<Long> second = null;
                if (options.maxBytes() == 0 && options.maxRequests() == 0) {
                    second = log.appendValuesAsync(List.of(MessageValue.copyOf(new byte[4])));
                    assertEquals(2, log.outstandingRequests());
                    assertEquals(72, log.outstandingBytes());
                } else {
                    assertThrows(LogCapacityExceededException.class,
                            () -> log.appendValuesAsync(List.of(MessageValue.copyOf(new byte[4]))));
                    assertEquals(1, log.outstandingRequests());
                }
                delegate.release.countDown();
                assertEquals(0, first.get(3, TimeUnit.SECONDS));
                if (second == null) second = log.appendValuesAsync(List.of(MessageValue.copyOf(new byte[4])));
                closeWithinTimeout(log);
                assertEquals(1, second.get(3, TimeUnit.SECONDS));
                assertEquals(0, log.outstandingBytes());
                assertEquals(0, log.outstandingRequests());
                assertEquals(2, log.nextOffset());
            } finally { delegate.release.countDown(); closeWithinTimeout(log); }
        }
    }

    @Test void unlimitedQueueStillCoalescesSmallWritesAndLargeBatchesBypassLinger() throws Exception {
        try (var log = new GroupCommitMessageLog(new InMemoryMessageLog(1024), 0, 0,
                TimeUnit.SECONDS.toNanos(30)); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var large = workers.submit(() -> log.appendBatch(java.util.Collections.nCopies(256, new byte[1024])));
            assertEquals(0, large.get(3, TimeUnit.SECONDS));
            assertEquals(256, log.nextOffset());
        }
    }

    @Test void successfulCompletionCanCloseWhileAnotherAcceptedWriteStillNeedsFlushing() throws Exception {
        var delegate = new CallbackLog(new ChronicleMessageLog(directory, 16, 64L << 20), false);
        var log = new GroupCommitMessageLog(delegate, 1024, 16, 0);
        try {
            var first = log.appendValuesAsync(List.of(MessageValue.copyOf(new byte[]{1})));
            assertTrue(delegate.entered.await(3, TimeUnit.SECONDS));
            var second = log.appendValuesAsync(List.of(MessageValue.copyOf(new byte[]{2})));
            var closing = first.thenRun(() -> {
                try { log.close(); }
                catch (IOException failure) { throw new CompletionException(failure); }
            });
            assertFalse(first.isDone(), "the callback must not run before the durable append completes");
            delegate.release.countDown();
            closing.get(3, TimeUnit.SECONDS);
            assertEquals(0, first.get(3, TimeUnit.SECONDS));
            assertEquals(1, second.get(3, TimeUnit.SECONDS));
            assertEquals(0, log.outstandingRequests());
            assertEquals(0, log.outstandingBytes());
            assertEquals(1, delegate.closes.get());
        } finally { delegate.release.countDown(); closeWithinTimeout(log); }
        try (var reopened = new ChronicleMessageLog(directory, 16, 64L << 20)) {
            assertEquals(2, reopened.nextOffset());
            var records = reopened.read(0, 16);
            assertArrayEquals(new byte[]{1}, records.get(0).value());
            assertArrayEquals(new byte[]{2}, records.get(1).value());
        }
    }

    @Test void successfulCompletionCanMakeAnotherSynchronousAppend() throws Exception {
        var delegate = new CallbackLog(new InMemoryMessageLog(16), false);
        var log = new GroupCommitMessageLog(delegate, 1024, 16, 0);
        try {
            var first = log.appendValuesAsync(List.of(MessageValue.copyOf(new byte[]{1})));
            assertTrue(delegate.entered.await(3, TimeUnit.SECONDS));
            var appended = first.thenApply(offset -> {
                try { return log.append(new byte[]{2}); }
                catch (IOException failure) { throw new CompletionException(failure); }
            });
            delegate.release.countDown();
            assertEquals(1, appended.get(3, TimeUnit.SECONDS));
            assertEquals(2, log.nextOffset());
        } finally { delegate.release.countDown(); closeWithinTimeout(log); }
    }

    @Test void failedCompletionCanCloseAndOtherFailedCompletionsStillFinish() throws Exception {
        var delegate = new CallbackLog(new InMemoryMessageLog(16), true);
        var log = new GroupCommitMessageLog(delegate, 1024, 16, 0);
        try {
            var first = log.appendValuesAsync(List.of(MessageValue.copyOf(new byte[]{1})));
            assertTrue(delegate.entered.await(3, TimeUnit.SECONDS));
            var second = log.appendValuesAsync(List.of(MessageValue.copyOf(new byte[]{2})));
            var closing = first.handle((offset, failure) -> {
                assertNull(offset);
                assertSame(delegate.failure, failure);
                try { log.close(); }
                catch (IOException problem) { throw new CompletionException(problem); }
                return null;
            });
            delegate.release.countDown();
            closing.get(3, TimeUnit.SECONDS);
            assertSame(delegate.failure, assertThrows(ExecutionException.class,
                    () -> second.get(3, TimeUnit.SECONDS)).getCause());
            assertEquals(0, log.nextOffset());
            assertEquals(0, log.outstandingRequests());
            assertEquals(0, log.outstandingBytes());
            assertEquals(1, delegate.closes.get());
        } finally { delegate.release.countDown(); closeWithinTimeout(log); }
    }

    @Test void blockedCallbackDoesNotDelayOtherCompletionsOrClose() throws Exception {
        var delegate = new CallbackLog(new InMemoryMessageLog(16), false);
        var callbackEntered = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        var log = new GroupCommitMessageLog(delegate, 1024, 16, 0);
        try {
            var first = log.appendValuesAsync(List.of(MessageValue.copyOf(new byte[]{1})));
            assertTrue(delegate.entered.await(3, TimeUnit.SECONDS));
            var blocked = first.thenRun(() -> {
                callbackEntered.countDown();
                try {
                    releaseCallback.await();
                } catch (InterruptedException failure) { throw new CompletionException(failure); }
            });
            delegate.release.countDown();
            assertTrue(callbackEntered.await(3, TimeUnit.SECONDS));
            assertEquals(1, log.appendValuesAsync(List.of(MessageValue.copyOf(new byte[]{2})))
                    .get(3, TimeUnit.SECONDS));
            closeWithinTimeout(log);
            assertFalse(blocked.isDone(), "close drains writes without waiting for caller callbacks");
            releaseCallback.countDown();
            blocked.get(3, TimeUnit.SECONDS);
        } finally { delegate.release.countDown(); releaseCallback.countDown(); closeWithinTimeout(log); }
    }

    private static void closeWithinTimeout(MessageLog log) throws Exception {
        var closed = new CompletableFuture<Void>();
        Thread.ofVirtual().start(() -> {
            try { log.close(); closed.complete(null); }
            catch (Throwable failure) { closed.completeExceptionally(failure); }
        });
        closed.get(3, TimeUnit.SECONDS);
    }

    private static final class CallbackLog implements MessageLog {
        private final MessageLog delegate;
        private final boolean fail;
        final IOException failure = new IOException("injected append failure");
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger closes = new AtomicInteger();

        CallbackLog(MessageLog delegate, boolean fail) { this.delegate = delegate; this.fail = fail; }
        public long append(byte[] value) throws IOException { return appendValues(List.of(MessageValue.copyOf(value))); }
        public long appendValues(List<MessageValue> values) throws IOException {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("append timed out");
            } catch (InterruptedException problem) { Thread.currentThread().interrupt(); throw new IOException(problem); }
            if (fail) throw failure;
            return delegate.appendValues(values);
        }
        public List<LogRecord> read(long offset, int bytes) throws IOException { return delegate.read(offset, bytes); }
        public long nextOffset() { return delegate.nextOffset(); }
        public void close() throws IOException { closes.incrementAndGet(); delegate.close(); }
    }

    @Test void asynchronousAdmissionReturnsBeforeForceAndCloseDrainsAcceptedWork() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        MessageLog blocked = new MessageLog() {
            private long next;
            public long append(byte[] value) throws java.io.IOException {
                entered.countDown();
                try { release.await(); } catch (InterruptedException failure) { throw new java.io.IOException(failure); }
                return next++;
            }
            public List<LogRecord> read(long offset, int bytes) { return List.of(); }
            public long nextOffset() { return next; }
            public void close() { }
        };
        var log = new GroupCommitMessageLog(blocked, 1024, 1, 0);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var accepted = log.appendValuesAsync(List.of(MessageValue.copyOf(new byte[]{1})));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertFalse(accepted.isDone());
                assertThrows(LogCapacityExceededException.class,
                        () -> log.appendValuesAsync(List.of(MessageValue.copyOf(new byte[]{2}))));
                var closing = workers.submit(() -> { log.close(); return true; });
                assertFalse(closing.isDone());
                release.countDown();
                assertEquals(0, accepted.get(3, TimeUnit.SECONDS));
                assertTrue(closing.get(3, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                log.close();
            }
        }
    }
    @Test void fullSizedBatchBypassesLingerWithoutWaitingForAnotherProducer() throws Exception {
        try (var log = new GroupCommitMessageLog(new InMemoryMessageLog(1024),
                32L * 1024 * 1024, 1024, TimeUnit.SECONDS.toNanos(30));
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = workers.submit(() -> log.appendBatch(java.util.Collections.nCopies(256, new byte[1024])));
            assertEquals(0L, result.get(3, TimeUnit.SECONDS));
            assertEquals(256, log.nextOffset());
            assertEquals(0, log.outstandingRequests());
        }
    }
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
        var file = new ChronicleMessageLog(directory, 64, 1024);
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
        try (var recovered = new ChronicleMessageLog(directory, 64, 1024)) {
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
