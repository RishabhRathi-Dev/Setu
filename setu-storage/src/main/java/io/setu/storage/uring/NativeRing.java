package io.setu.storage.uring;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.lang.foreign.ValueLayout.*;

/** Linux's stable, little-endian 64-bit UAPI. No native shim or liburing dependency. */
final class NativeRing implements UringRing {
    private static final VarHandle INT = JAVA_INT.varHandle();
    // On an unrecoverable enter/protocol error, buffers must not be freed while
    // the kernel might still use them. Quarantine the ring until process exit.
    private static final ConcurrentLinkedQueue<NativeRing> QUARANTINED = new ConcurrentLinkedQueue<>();
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment errors = arena.allocate(UringNativeCalls.ERRORS);
    private final MemorySegment buffers;
    private final int stride;
    private final UringOperation[] operations;
    // Separate from reusable commands: a fatal CQ error must retain borrowed
    // memory even after a caller receives an error and closes its file handle.
    private final MemorySegment[] borrowed;
    private final long[] tags;
    private final Arena wakeArena = Arena.ofShared();
    private final MemorySegment wakeErrors = wakeArena.allocate(UringNativeCalls.ERRORS);
    private final MemorySegment wakeValue = wakeArena.allocate(JAVA_LONG);
    private final MemorySegment wakeRead = arena.allocate(JAVA_LONG);
    private final Object wakeLock = new Object();
    private int wakeFd = -1;
    private int pending;
    private boolean poisoned, awakened;
    private int fd = -1;
    private MemorySegment sq, cq, sqes;
    private long sqHead, sqTail, sqArray, sqDropped, cqHead, cqTail, cqEntries, cqOverflow;
    private int sqMask, cqMask;
    private long nextTag = 1;
    private boolean closed;

    NativeRing(UringOptions options) throws IOException {
        stride = Math.max(4096, options.maxWriteBytes());
        // Reserve an entry for eventfd polling so new work wakes completion waits.
        operations = new UringOperation[options.entries() - 1];
        borrowed = new MemorySegment[operations.length];
        tags = new long[operations.length];
        MemorySegment allocated;
        try {
            MemorySegment params = arena.allocate(120, 8);
            long descriptor = (long) UringNativeCalls.SETUP.invokeExact(errors, 425L, options.entries(), params);
            if (descriptor < 0) {
                int errno = errors.get(JAVA_INT, UringNativeCalls.ERRNO);
                if (errno == 1 || errno == 13 || errno == 38 || errno == 95)
                    throw new UringUnavailableException(errno);
                throw error("io_uring_setup");
            }
            fd = Math.toIntExact(descriptor);
            int sqCount = params.get(JAVA_INT, 0), cqCount = params.get(JAVA_INT, 4);
            if (sqCount < options.entries() || Integer.bitCount(sqCount) != 1 || cqCount < sqCount
                    || sqCount > 65536 || cqCount > 131072) throw new IOException("Unsupported io_uring capacity");
            sqHead = offset(params, 40); sqTail = offset(params, 44);
            sqDropped = offset(params, 60); sqArray = offset(params, 64);
            cqHead = offset(params, 80); cqTail = offset(params, 84);
            cqOverflow = offset(params, 96); cqEntries = offset(params, 100);
            long sqBytes = Math.addExact(sqArray, 4L * sqCount);
            long cqBytes = Math.addExact(cqEntries, 16L * cqCount);
            if ((params.get(JAVA_INT, 20) & 1) != 0) {
                sq = map(Math.max(sqBytes, cqBytes), 0);
                cq = sq;
            } else {
                sq = map(sqBytes, 0);
                cq = map(cqBytes, 0x8000000L);
            }
            sqes = map(64L * sqCount, 0x10000000L);
            sqMask = sq.get(JAVA_INT, offset(params, 48));
            cqMask = cq.get(JAVA_INT, offset(params, 88));
            if (sqMask != sqCount - 1 || cqMask != cqCount - 1) throw new IOException("Invalid io_uring masks");
            allocated = arena.allocate(options.nativeBufferBytes(), 64);
            wakeFd = (int) UringNativeCalls.EVENTFD.invokeExact(errors, 0, 0x80800); // EFD_CLOEXEC | EFD_NONBLOCK
            if (wakeFd < 0) throw error("eventfd");
            wakeValue.set(JAVA_LONG, 0, 1L);
            armWakeup();
        } catch (Throwable failure) {
            if (wakeFd >= 0) try { closeFile(wakeFd); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            if (fd >= 0) try { closeFile(fd); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            arena.close();
            wakeArena.close();
            throw asIo(failure);
        }
        buffers = allocated;
    }
    private static long offset(MemorySegment params, long position) { return Integer.toUnsignedLong(params.get(JAVA_INT, position)); }
    private IOException error(String operation) {
        return new IOException(operation + " failed: errno=" + errors.get(JAVA_INT, UringNativeCalls.ERRNO));
    }
    private static IOException asIo(Throwable failure) {
        return failure instanceof IOException io ? io : new IOException("io_uring native operation failed", failure);
    }
    private MemorySegment map(long bytes, long offset) throws Throwable {
        MemorySegment address = (MemorySegment) UringNativeCalls.MMAP.invokeExact(errors, MemorySegment.NULL, bytes,
                3, 1, fd, offset); // PROT_READ|PROT_WRITE, MAP_SHARED
        if (address.address() == -1L) throw error("mmap");
        return address.reinterpret(bytes, arena, segment -> {
            try {
                if ((int) UringNativeCalls.MUNMAP.invokeExact(segment, bytes) != 0)
                    throw new IllegalStateException("Cannot unmap io_uring");
            } catch (Throwable failure) { throw new IllegalStateException("Cannot unmap io_uring", failure); }
        });
    }
    private static int acquire(MemorySegment memory, long offset) { return (int) INT.getAcquire(memory, offset); }
    private static void release(MemorySegment memory, long offset, int value) { INT.setRelease(memory, offset, value); }

    @Override public int available() { return operations.length - pending; }

    @Override public void submit(List<UringOperation> batch) throws IOException {
        if (closed || poisoned || batch.size() > available()) throw new IOException("io_uring is closed, failed or full");
        try {
            int tail = sq.get(JAVA_INT, sqTail);
            for (int i = 0; i < batch.size(); i++) {
                UringOperation operation = batch.get(i);
                int slot = 0;
                while (operations[slot] != null) slot++;
                long tag = Math.multiplyExact(nextTag++, 256) | slot;
                int index = (tail + i) & sqMask;
                MemorySegment entry = sqes.asSlice(index * 64L, 64);
                MemorySegment data = buffers.asSlice((long) slot * stride, stride);
                entry.fill((byte) 0);
                entry.set(JAVA_BYTE, 0, (byte) operation.opcode);
                entry.set(JAVA_INT, 4, operation.file.fd);
                entry.set(JAVA_LONG, 8, operation.offset);
                entry.set(JAVA_LONG, 32, tag);
                if (operation.opcode == UringFileIo.OPEN) {
                    MemorySegment.copy(MemorySegment.ofArray(operation.path), 0, data, 0, operation.path.length);
                    entry.set(JAVA_INT, 4, -100); // AT_FDCWD, absolute path
                    entry.set(JAVA_LONG, 16, data.address());
                    entry.set(JAVA_INT, 28, 2 | 0x80000); // O_RDWR | O_CLOEXEC, no create/truncate
                } else if (operation.opcode == UringFileIo.WRITE) {
                    MemorySegment source = MemorySegment.ofBuffer(operation.payload);
                    if (source.isNative() && UringFileIo.ownsBuffer(source)) {
                        borrowed[slot] = source;
                        data = source;
                    } else {
                        MemorySegment.copy(source, 0, data, 0, operation.bytes());
                    }
                    entry.set(JAVA_LONG, 16, data.address());
                    entry.set(JAVA_INT, 24, operation.bytes());
                } else if (operation.opcode == UringFileIo.FSYNC) {
                    entry.set(JAVA_INT, 28, operation.metadata ? 0 : 1); // IORING_FSYNC_DATASYNC
                }
                sq.set(JAVA_INT, sqArray + index * 4L, index);
                operations[slot] = operation;
                tags[slot] = tag;
                pending++;
            }
            // Payload copies and retained direct references precede publishing SQEs.
            release(sq, sqTail, tail + batch.size());
            enter(false);
        } catch (Throwable failure) { poisoned = true; throw asIo(failure); }
    }

    @Override public List<UringCompletion> poll(boolean wait) throws IOException {
        try {
            List<UringCompletion> ready = drain();
            if (wait && ready.isEmpty() && !awakened) {
                enter(true);
                ready = drain();
            }
            return ready;
        } catch (Throwable failure) { poisoned = true; throw asIo(failure); }
    }
    private List<UringCompletion> drain() throws Throwable {
        var results = new ArrayList<UringCompletion>();
        awakened = false;
        int head = cq.get(JAVA_INT, cqHead), ready = acquire(cq, cqTail);
        while (head != ready) {
            MemorySegment completion = cq.asSlice(cqEntries + (head & cqMask) * 16L, 16);
            long tag = completion.get(JAVA_LONG, 0);
            int result = completion.get(JAVA_INT, 8);
            if (tag == 0) {
                if (result < 0 || awakened) throw new IOException("Invalid eventfd poll completion: " + result);
                awakened = true;
                drainWakeup();
            } else {
                int slot = (int) (tag & 255);
                if (slot >= operations.length || operations[slot] == null || tags[slot] != tag)
                    throw new IOException("Unexpected or duplicate io_uring completion");
                results.add(new UringCompletion(operations[slot], result));
                operations[slot] = null;
                borrowed[slot] = null;
                pending--;
            }
            head++;
        }
        release(cq, cqHead, head);
        if (sq.get(JAVA_INT, sqDropped) != 0 || cq.get(JAVA_INT, cqOverflow) != 0)
            throw new IOException("io_uring dropped submissions or completions");
        if (awakened) armWakeup();
        return results;
    }
    private void enter(boolean wait) throws Throwable {
        while (true) {
            int submissions = sq.get(JAVA_INT, sqTail) - acquire(sq, sqHead);
            long result = (long) UringNativeCalls.ENTER.invokeExact(errors, 426L, fd, submissions, wait ? 1 : 0, wait ? 1 : 0,
                    MemorySegment.NULL, 0L);
            if (result >= 0) return;
            int errno = errors.get(JAVA_INT, UringNativeCalls.ERRNO);
            if (errno == 4) continue;
            if (errno == 11 || errno == 16) return; // Reap CQ before retrying EAGAIN/EBUSY.
            throw error("io_uring_enter");
        }
    }
    private void armWakeup() {
        int tail = sq.get(JAVA_INT, sqTail), index = tail & sqMask;
        MemorySegment entry = sqes.asSlice(index * 64L, 64);
        entry.fill((byte) 0);
        entry.set(JAVA_BYTE, 0, (byte) 6); // IORING_OP_POLL_ADD, one-shot
        entry.set(JAVA_INT, 4, wakeFd);
        entry.set(JAVA_INT, 28, 1); // POLLIN
        sq.set(JAVA_INT, sqArray + index * 4L, index);
        release(sq, sqTail, tail + 1);
    }
    private void drainWakeup() throws Throwable {
        while (true) {
            long result = (long) UringNativeCalls.READ.invokeExact(errors, wakeFd, wakeRead, 8L);
            if (result == 8) continue;
            int errno = errors.get(JAVA_INT, UringNativeCalls.ERRNO);
            if (result < 0 && errno == 11) return;
            if (result < 0 && errno == 4) continue;
            throw error("eventfd read");
        }
    }
    @Override public void wakeup() throws IOException {
        synchronized (wakeLock) {
            if (closed) return;
            try {
                while (true) {
                    long result = (long) UringNativeCalls.WRITE.invokeExact(wakeErrors, wakeFd, wakeValue, 8L);
                    if (result == 8) return;
                    int errno = wakeErrors.get(JAVA_INT, UringNativeCalls.ERRNO);
                    if (result < 0 && errno == 11) return; // Counter is already readable.
                    if (result < 0 && errno == 4) continue;
                    throw new IOException("eventfd write failed: errno=" + errno);
                }
            } catch (Throwable failure) { throw asIo(failure); }
        }
    }

    @Override public void closeFile(int descriptor) throws IOException {
        if (poisoned && pending > 0) throw new IOException("Descriptor retained because kernel I/O is unresolved");
        try {
            // Do not retry close on EINTR: Linux already released the descriptor.
            if ((int) UringNativeCalls.CLOSE.invokeExact(errors, descriptor) != 0) throw error("close");
        } catch (Throwable failure) { throw asIo(failure); }
    }
    @Override public void close() throws IOException {
        synchronized (wakeLock) { if (closed) return; closed = true; }
        if (pending > 0) {
            QUARANTINED.add(this);
            throw new IOException("io_uring has unresolved kernel operations; native memory retained until process exit");
        }
        try { closeFile(fd); }
        finally {
            try { closeFile(wakeFd); }
            finally { arena.close(); wakeArena.close(); }
        }
    }
}
