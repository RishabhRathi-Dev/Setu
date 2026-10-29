package io.setu.storage.uring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class UringFileIoTests {
    private static final UringOptions SMALL = new UringOptions(2, 8, 4, 16);
    private record ReleaseGate(CountDownLatch gate) implements AutoCloseable {
        @Override public void close() { gate.countDown(); }
    }
    private static class FakeRing implements UringRing {
        final AtomicInteger descriptors = new AtomicInteger(100);
        final List<Integer> closedFiles = new ArrayList<>();
        final List<Long> offsets = new ArrayList<>();
        final List<Byte> written = new ArrayList<>();
        final List<Boolean> forces = new ArrayList<>();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final List<UringOperation> pending = new ArrayList<>();
        final java.util.concurrent.Semaphore signals = new java.util.concurrent.Semaphore(0);
        volatile CountDownLatch entered, release;
        volatile IOException fatal;
        volatile int writeError;
        volatile int blockedDescriptor = -1;
        int forceError, shortWrite = Integer.MAX_VALUE;
        volatile boolean closed;
        @Override public int available() { return 2 - pending.size(); }
        @Override public void submit(List<UringOperation> batch) {
            pending.addAll(batch);
            maxInFlight.accumulateAndGet(pending.size(), Math::max);
            if (entered != null && batch.stream().anyMatch(op -> op.opcode == UringFileIo.WRITE)) entered.countDown();
        }
        @Override public void wakeup() { signals.release(); }
        @Override public List<UringCompletion> poll(boolean wait) throws IOException {
            var result = drain();
            if (wait && result.isEmpty()) {
                try { signals.tryAcquire(10, TimeUnit.MILLISECONDS); }
                catch (InterruptedException failure) { throw new IOException(failure); }
                result = drain();
            }
            return result;
        }
        private List<UringCompletion> drain() throws IOException {
            var results = new ArrayList<UringCompletion>();
            var iterator = pending.iterator();
            while (iterator.hasNext()) {
                var operation = iterator.next();
                if (operation.opcode == UringFileIo.WRITE && release != null && release.getCount() > 0
                        && (blockedDescriptor == -1 || blockedDescriptor == operation.file.fd)) continue;
                int result = 0;
                switch (operation.opcode) {
                    case UringFileIo.OPEN -> result = descriptors.getAndIncrement();
                    case UringFileIo.CLOSE -> closedFiles.add(operation.file.fd);
                    case UringFileIo.FSYNC -> { forces.add(operation.metadata); result = forceError; }
                    case UringFileIo.WRITE -> {
                        if (fatal != null) throw fatal;
                        if (writeError != 0) { result = writeError; break; }
                        offsets.add(operation.offset);
                        int length = Math.min(operation.bytes(), shortWrite);
                        for (int b = 0; b < length; b++) written.add(operation.payload.get(operation.payload.position() + b));
                        result = length;
                    }
                    default -> throw new AssertionError("Unexpected operation");
                }
                results.add(new UringCompletion(operation, result));
                iterator.remove();
            }
            return results;
        }
        @Override public void closeFile(int fd) { closedFiles.add(fd); }
        @Override public void close() { closed = true; }
    }
    private static UringFileIo create(FakeRing ring) throws IOException { return new UringFileIo(SMALL, ignored -> ring); }
    private static void until(java.util.function.BooleanSupplier condition) throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < limit) Thread.sleep(1);
        assertTrue(condition.getAsBoolean(), "Condition not reached");
    }

    @Test void completesShortWritesAndChunksWithoutLosingOffsetsOrChangingLimit() throws Exception {
        FakeRing ring = new FakeRing(); ring.shortWrite = 3;
        try (var io = create(ring); var file = io.open(Path.of("existing"))) {
            ByteBuffer data = ByteBuffer.wrap(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}).position(2).limit(11);
            file.writeFully(data, 50);
            assertEquals(11, data.position());
            assertEquals(11, data.limit());
            assertEquals(List.of(50L, 53L, 56L), ring.offsets);
            assertEquals(List.of((byte) 2, (byte) 3, (byte) 4, (byte) 5, (byte) 6, (byte) 7, (byte) 8, (byte) 9, (byte) 10), ring.written);
            file.force(true); file.force(false);
            assertEquals(List.of(true, false), ring.forces);
            assertEquals(0, io.outstandingBytes());
            assertEquals(0, io.outstandingRequests());
        }
        assertEquals(List.of(100), ring.closedFiles);
        assertTrue(ring.closed);
    }

    @Test void callerConfinedNativeBuffersAreCopiedBeforeCrossingThreads() throws Exception {
        FakeRing ring = new FakeRing(); ring.shortWrite = 3;
        try (var io = create(ring); var file = io.open(Path.of("existing")); var arena = Arena.ofConfined()) {
            ByteBuffer source = arena.allocate(16).asByteBuffer();
            source.put(new byte[]{0, 1, 2, 3, 4, 5}).flip().position(1);
            file.writeFully(source.asReadOnlyBuffer(), 9);
            assertEquals(1, source.position());
            assertEquals(List.of((byte) 1, (byte) 2, (byte) 3, (byte) 4, (byte) 5), ring.written);
            assertEquals(List.of(9L, 12L), ring.offsets);
        }
    }

    @Test void onlyOwnedAllocationsAndTheirViewsQualifyForRetainedSubmission() throws Exception {
        ByteBuffer owned = UringFileIo.allocateBuffer(16).put(new byte[]{1, 2, 3, 4}).flip();
        assertTrue(UringFileIo.ownsBuffer(MemorySegment.ofBuffer(owned.slice().asReadOnlyBuffer())));
        assertFalse(UringFileIo.ownsBuffer(MemorySegment.ofBuffer(ByteBuffer.allocateDirect(16))));
        try (var arena = Arena.ofShared()) {
            assertFalse(UringFileIo.ownsBuffer(arena.allocate(16)));
        }
        FakeRing ring = new FakeRing() {
            @Override public void submit(List<UringOperation> batch) {
                for (var operation : batch) {
                    if (operation.opcode == UringFileIo.WRITE)
                        assertTrue(operation.payload.isDirect(), "Owned buffer must survive dispatch without a heap copy");
                }
                super.submit(batch);
            }
        };
        ring.shortWrite = 2;
        try (var io = create(ring); var file = io.open(Path.of("owned"))) {
            file.writeFully(owned.asReadOnlyBuffer(), 0);
            assertEquals(List.of((byte) 1, (byte) 2, (byte) 3, (byte) 4), ring.written);
        }
    }

    @Test void idleOwnerIsWokenBySubmissionAndShutdownWithoutPollingTimeouts() throws Exception {
        CountDownLatch sleeping = new CountDownLatch(1);
        FakeRing ring = new FakeRing() {
            @Override public List<UringCompletion> poll(boolean wait) throws IOException {
                if (wait && pending.isEmpty()) {
                    sleeping.countDown();
                    try { signals.acquire(); }
                    catch (InterruptedException failure) { throw new IOException(failure); }
                }
                return super.poll(false);
            }
        };
        try (var io = create(ring)) {
            assertTrue(sleeping.await(5, TimeUnit.SECONDS));
            try (var file = io.open(Path.of("after-idle"))) { file.force(false); }
        }
        assertTrue(ring.closed);
    }

    @Test void interruptionDoesNotReleaseAcceptedWorkAndCloseWaitsForIt() throws Exception {
        FakeRing ring = new FakeRing(); ring.entered = new CountDownLatch(1); ring.release = new CountDownLatch(1);
        var io = create(ring);
        var file = io.open(Path.of("existing"));
        AtomicBoolean returned = new AtomicBoolean(), interrupted = new AtomicBoolean();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread writer = Thread.ofPlatform().start(() -> {
            try { file.writeFully(ByteBuffer.allocate(8), 0); interrupted.set(Thread.currentThread().isInterrupted()); }
            catch (Throwable problem) { failure.set(problem); }
            finally { returned.set(true); }
        });
        try (var callers = Executors.newVirtualThreadPerTaskExecutor(); var release = new ReleaseGate(ring.release)) {
            assertTrue(ring.entered.await(5, TimeUnit.SECONDS));
            writer.interrupt();
            var closing = callers.submit(() -> { io.close(); return null; });
            assertFalse(returned.get());
            assertFalse(closing.isDone());
            assertEquals(8, io.outstandingBytes());
            ring.release.countDown();
            closing.get(5, TimeUnit.SECONDS);
            writer.join(5000);
            assertFalse(writer.isAlive());
            assertNull(failure.get());
            assertTrue(interrupted.get());
            assertTrue(ring.closed);
            assertEquals(List.of(100), ring.closedFiles);
            assertThrows(IOException.class, () -> file.force(false));
            file.close(); io.close();
        } finally { ring.release.countDown(); io.close(); }
    }

    @Test void byteAdmissionIncludesActiveWorkAndIndependentFilesBatch() throws Exception {
        FakeRing ring = new FakeRing(); ring.entered = new CountDownLatch(1); ring.release = new CountDownLatch(1);
        try (var io = create(ring); var first = io.open(Path.of("one")); var second = io.open(Path.of("two"));
             var third = io.open(Path.of("three")); var fourth = io.open(Path.of("four"));
             var callers = Executors.newVirtualThreadPerTaskExecutor(); var release = new ReleaseGate(ring.release)) {
            var a = callers.submit(() -> { first.writeFully(ByteBuffer.allocate(8), 0); return null; });
            assertTrue(ring.entered.await(5, TimeUnit.SECONDS));
            var b = callers.submit(() -> { second.writeFully(ByteBuffer.allocate(8), 0); return null; });
            until(() -> io.outstandingBytes() == 16);
            ByteBuffer rejected = ByteBuffer.allocate(1);
            assertThrows(IOException.class, () -> third.writeFully(rejected, 0));
            assertEquals(0, rejected.position());
            var c = callers.submit(() -> { fourth.force(false); return null; });
            until(() -> io.outstandingRequests() == 3);
            ring.release.countDown();
            a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS); c.get(5, TimeUnit.SECONDS);
            assertEquals(2, ring.maxInFlight.get());
            assertEquals(16, ring.written.size());
            assertEquals(0, io.outstandingBytes());
        } finally { ring.release.countDown(); }
    }

    @Test void fastFileCompletesAndForcesWhileAnotherFileIsStillPending() throws Exception {
        FakeRing ring = new FakeRing();
        ring.entered = new CountDownLatch(1); ring.release = new CountDownLatch(1); ring.blockedDescriptor = 100;
        try (var io = create(ring); var slow = io.open(Path.of("slow")); var fast = io.open(Path.of("fast"));
             var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var blocked = callers.submit(() -> { slow.writeFully(ByteBuffer.allocate(8), 0); return null; });
            try {
                assertTrue(ring.entered.await(5, TimeUnit.SECONDS));
                var independent = callers.submit(() -> {
                    fast.writeFully(ByteBuffer.allocate(8), 0);
                    fast.force(true);
                    return null;
                });
                independent.get(5, TimeUnit.SECONDS);
                assertFalse(blocked.isDone());
                assertEquals(List.of(true), ring.forces);
                assertEquals(8, io.outstandingBytes());
            } finally { ring.release.countDown(); }
            blocked.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void brokerBackpressureWaitsForByteCreditWithoutPoisoningTheFile() throws Exception {
        FakeRing ring = new FakeRing(); ring.entered = new CountDownLatch(1); ring.release = new CountDownLatch(1);
        try (var io = new UringFileIo(SMALL, ignored -> ring, true);
             var a = io.open(Path.of("a")); var b = io.open(Path.of("b")); var c = io.open(Path.of("c"));
             var callers = Executors.newVirtualThreadPerTaskExecutor(); var release = new ReleaseGate(ring.release)) {
            var first = callers.submit(() -> { a.writeFully(ByteBuffer.allocate(8), 0); return null; });
            assertTrue(ring.entered.await(5, TimeUnit.SECONDS));
            var second = callers.submit(() -> { b.writeFully(ByteBuffer.allocate(8), 0); return null; });
            until(() -> io.outstandingBytes() == 16);
            var third = callers.submit(() -> { c.writeFully(ByteBuffer.wrap(new byte[]{9}), 0); c.force(true); return null; });
            assertThrows(java.util.concurrent.TimeoutException.class, () -> third.get(100, TimeUnit.MILLISECONDS));
            assertEquals(16, io.outstandingBytes());
            ring.release.countDown();
            first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS); third.get(5, TimeUnit.SECONDS);
            assertEquals(17, ring.written.size());
            assertEquals(0, io.outstandingRequests());
        }
    }

    @Test void failedCompletionPoisonsOnlyItsFileAndAlwaysAllowsClose() throws Exception {
        FakeRing ring = new FakeRing(); ring.writeError = -28;
        try (var io = create(ring); var first = io.open(Path.of("one")); var second = io.open(Path.of("two"))) {
            ByteBuffer source = ByteBuffer.allocate(8);
            assertTrue(assertThrows(IOException.class, () -> first.writeFully(source, 0)).getMessage().contains("errno=28"));
            assertEquals(0, source.position());
            assertThrows(IOException.class, () -> first.force(false));
            ring.writeError = 0;
            second.writeFully(ByteBuffer.allocate(8), 0);
            second.force(true);
            assertEquals(0, io.outstandingRequests());
        }
        assertEquals(2, ring.closedFiles.size());
    }

    @Test void forceFailureIsNotAcknowledgedAndStartupFailureTerminates() throws Exception {
        FakeRing ring = new FakeRing(); ring.forceError = -5;
        try (var io = create(ring); var file = io.open(Path.of("one"))) {
            assertThrows(IOException.class, () -> file.force(true));
            assertThrows(IOException.class, () -> file.writeFully(ByteBuffer.allocate(1), 0));
        }
        assertThrows(IOException.class, () -> new UringFileIo(SMALL, ignored -> { throw new IOException("setup denied"); }));
    }

    @Test void fatalOwnerFailureCompletesQueuedWaitersAndRejectsNewWork() throws Exception {
        FakeRing ring = new FakeRing(); ring.entered = new CountDownLatch(1); ring.release = new CountDownLatch(1);
        var io = create(ring);
        var a = io.open(Path.of("one")); var b = io.open(Path.of("two"));
        try (var callers = Executors.newVirtualThreadPerTaskExecutor(); var release = new ReleaseGate(ring.release)) {
            var first = callers.submit(() -> { a.writeFully(ByteBuffer.allocate(8), 0); return null; });
            assertTrue(ring.entered.await(5, TimeUnit.SECONDS));
            var second = callers.submit(() -> { b.writeFully(ByteBuffer.allocate(8), 0); return null; });
            until(() -> io.outstandingRequests() == 2);
            ring.fatal = new IOException("fatal enter error");
            ring.release.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
            assertThrows(IOException.class, () -> io.open(Path.of("three")));
            assertThrows(IOException.class, io::close);
            assertEquals(0, io.outstandingBytes());
            assertEquals(0, io.outstandingRequests());
            assertTrue(ring.closed);
            assertEquals(2, ring.closedFiles.size());
        } finally { ring.release.countDown(); try { io.close(); } catch (IOException expected) { } }
    }

    @Test void validatesLimitsAndRejectsUnsupportedPlatformsWithoutNativeInitialization() {
        assertThrows(IllegalArgumentException.class, () -> new UringOptions(3, 8, 4, 16));
        assertThrows(IllegalArgumentException.class, () -> new UringOptions(256, 16 * 1024 * 1024, 256, Long.MAX_VALUE));
        if (!UringFileIo.isSupportedPlatform()) assertThrows(IOException.class, UringFileIo::create);
    }

    @Test void cleanupExceptionsAreReportedWithoutStrandingCloseOrSkippingOtherResources() throws Exception {
        FakeRing ring = new FakeRing() {
            @Override public void closeFile(int fd) {
                super.closeFile(fd);
                if (fd == 100) throw new IllegalStateException("descriptor cleanup failed");
            }
            @Override public void close() {
                super.close();
                throw new IllegalStateException("mapping cleanup failed");
            }
        };
        var io = create(ring);
        var first = io.open(Path.of("one"));
        var second = io.open(Path.of("two"));
        IOException failure = assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                () -> assertThrows(IOException.class, io::close));
        assertEquals(2, ring.closedFiles.size());
        assertTrue(ring.closed);
        assertEquals(1, failure.getSuppressed().length);
        assertThrows(IOException.class, io::close);
        first.close(); second.close();
    }
}
