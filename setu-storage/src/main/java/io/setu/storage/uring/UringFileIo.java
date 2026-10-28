package io.setu.storage.uring;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Linux file I/O with one bounded, batching io_uring owner. Operations on
 * each file serialize; independent files can submit together. Success from
 * writeFully is not durable until force succeeds. Close drains accepted work.
 */
public final class UringFileIo implements AutoCloseable {
    /** Only unsupported or policy-denied setup permits the broker's startup fallback. */
    public static final class UnavailableException extends IOException {
        public UnavailableException(int errno) {
            super("io_uring setup unavailable: errno=" + errno);
            if (errno != 1 && errno != 13 && errno != 38 && errno != 95)
                throw new IllegalArgumentException("Not an availability error: " + errno);
        }
    }
    public record Options(int entries, int maxWriteBytes, int maxPendingRequests, long maxPendingBytes) {
        public Options {
            if (entries < 2 || entries > 256 || Integer.bitCount(entries) != 1
                    || maxWriteBytes < 1 || maxWriteBytes > 16 * 1024 * 1024
                    || (long) entries * Math.max(4096, maxWriteBytes) > 64L * 1024 * 1024
                    || maxPendingRequests < entries || maxPendingRequests > 65536
                    || maxPendingBytes < maxWriteBytes)
                throw new IllegalArgumentException("Invalid io_uring admission or native-buffer limits");
        }
        public static Options defaults() { return new Options(16, 1024 * 1024, 1024, 32L * 1024 * 1024); }
        public long nativeBufferBytes() { return (long) entries * Math.max(4096, maxWriteBytes); }
    }

    // Linux UAPI opcodes, not a dependency on a third-party Java implementation.
    static final int FSYNC = 3, OPEN = 18, CLOSE = 19, WRITE = 23;
    static final class Operation {
        int opcode;
        final File file;
        ByteBuffer payload;
        long offset;
        boolean metadata;
        byte[] path;
        CompletableFuture<Integer> completion;
        Operation(int opcode, File file, ByteBuffer payload, long offset, boolean metadata, byte[] path) {
            this.file = file;
            reset(opcode, payload, offset, metadata, path);
        }
        Operation reset(int opcode, ByteBuffer payload, long offset, boolean metadata, byte[] path) {
            this.opcode = opcode; this.payload = payload;
            this.offset = offset; this.metadata = metadata; this.path = path;
            completion = new CompletableFuture<>();
            return this;
        }
        int bytes() { return payload == null ? 0 : payload.remaining(); }
    }
    record Completion(Operation operation, int result) { }
    interface Ring extends AutoCloseable {
        int available();
        void submit(List<Operation> operations) throws IOException;
        List<Completion> poll(boolean wait) throws IOException;
        void wakeup() throws IOException;
        void closeFile(int fd) throws IOException;
        @Override void close() throws IOException;
    }
    @FunctionalInterface interface RingFactory { Ring open(Options options) throws IOException; }

    private static final AtomicLong IDS = new AtomicLong();
    // Weak keys do not keep unused allocations alive. Only buffers allocated
    // here are known to have GC-managed lifetimes, rather than closeable arenas.
    private static final WeakHashMap<MemorySegment.Scope, Boolean> BUFFER_SCOPES = new WeakHashMap<>();
    private final Options options;
    private final boolean waitForAdmission;
    private final Object admission = new Object();
    private final ConcurrentLinkedQueue<Operation> queue = new ConcurrentLinkedQueue<>();
    private final Set<File> files = new HashSet<>(); // Only the owner thread touches descriptors.
    private final Thread worker;
    private final CompletableFuture<Void> started = new CompletableFuture<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private boolean accepting = true;
    private int requests;
    private long bytes;
    private volatile Ring activeRing;
    private final AtomicBoolean waiting = new AtomicBoolean();

    public static boolean isSupportedPlatform() {
        String architecture = System.getProperty("os.arch");
        return "Linux".equals(System.getProperty("os.name")) && ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                && ("amd64".equals(architecture) || "x86_64".equals(architecture) || "aarch64".equals(architecture));
    }
    public static UringFileIo create() throws IOException { return create(Options.defaults()); }
    /** Broker default: bounded admission waits for credit instead of failing an accepted append. */
    public static UringFileIo createWithBackpressure() throws IOException {
        if (!isSupportedPlatform()) throw new IOException("io_uring requires little-endian Linux x86-64 or AArch64");
        return new UringFileIo(Options.defaults(), NativeRing::new, true);
    }
    /**
     * Allocates a GC-managed direct buffer eligible for zero-copy submission.
     * Slices/read-only views preserve this eligibility. Uses the JDK direct-memory
     * budget; callers must keep contents unchanged until writeFully returns.
     */
    public static ByteBuffer allocateBuffer(int capacity) {
        MemorySegment segment = MemorySegment.ofBuffer(ByteBuffer.allocateDirect(capacity));
        synchronized (BUFFER_SCOPES) { BUFFER_SCOPES.put(segment.scope(), Boolean.TRUE); }
        // Preserve this exact automatic scope in all subsequent buffer views.
        return segment.asByteBuffer();
    }
    static boolean ownsBuffer(MemorySegment segment) {
        synchronized (BUFFER_SCOPES) { return BUFFER_SCOPES.containsKey(segment.scope()); }
    }
    public static UringFileIo create(Options options) throws IOException {
        if (!isSupportedPlatform()) throw new IOException("io_uring requires little-endian Linux x86-64 or AArch64");
        return new UringFileIo(options, NativeRing::new);
    }
    UringFileIo(Options options, RingFactory factory) throws IOException {
        this(options, factory, false);
    }
    UringFileIo(Options options, RingFactory factory, boolean waitForAdmission) throws IOException {
        this.options = java.util.Objects.requireNonNull(options);
        this.waitForAdmission = waitForAdmission;
        worker = Thread.ofPlatform().name("setu-io-uring-" + IDS.incrementAndGet())
                .unstarted(() -> run(factory));
        worker.start();
        try { await(started); }
        catch (IOException failure) {
            try { await(stopped); } catch (IOException cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Opens an existing file read/write, without creating or truncating it. */
    public File open(Path path) throws IOException {
        String name = path.toAbsolutePath().toString();
        if (name.indexOf('\0') >= 0) throw new IOException("NUL in file path");
        byte[] encoded = (name + '\0').getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 4096) throw new IOException("File path exceeds Linux PATH_MAX");
        File file = new File(this);
        perform(file.operation.reset(OPEN, null, 0, false, encoded), false);
        return file;
    }
    public int outstandingRequests() { synchronized (admission) { return requests; } }
    public long outstandingBytes() { synchronized (admission) { return bytes; } }
    public Options options() { return options; }

    private int perform(Operation operation, boolean closingFile) throws IOException {
        boolean interrupted = false;
        boolean shuttingDown = false;
        IOException wakeFailure = null;
        try {
            synchronized (admission) {
                // Descriptor close waits for admission instead of leaking a descriptor on saturation.
                while ((closingFile || waitForAdmission) && accepting
                        && (requests == options.maxPendingRequests() || operation.bytes() > options.maxPendingBytes() - bytes)) {
                    try { admission.wait(); } catch (InterruptedException ignored) { interrupted = true; }
                }
                if (!accepting) {
                    if (!closingFile) throw new IOException("io_uring executor is closing or failed");
                    shuttingDown = true;
                } else {
                    if (requests >= options.maxPendingRequests() || operation.bytes() > options.maxPendingBytes() - bytes)
                        throw new IOException("io_uring admission limit exceeded");
                    requests++; bytes += operation.bytes();
                    queue.add(operation);
                }
            }
            if (shuttingDown) { await(stopped); return 0; }
            try { wakeup(); } catch (IOException problem) { wakeFailure = problem; }
            int result = await(operation.completion);
            if (wakeFailure != null) throw wakeFailure;
            return result;
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private void wakeup() throws IOException {
        // Coalesce concurrent producers to one eventfd write per sleep transition.
        if (waiting.compareAndSet(true, false)) activeRing.wakeup();
    }

    private void finish(Operation operation, Integer result, IOException failure) {
        if (result != null) {
            if (operation.opcode == OPEN && result >= 0) {
                operation.file.fd = result;
                files.add(operation.file);
            } else if (operation.opcode == CLOSE) {
                // Linux close releases the descriptor even when it reports an I/O error.
                operation.file.closed = true;
                files.remove(operation.file);
            }
            if (result < 0) failure = new IOException("io_uring opcode " + operation.opcode + " failed: errno=" + -(long) result);
        }
        synchronized (admission) {
            requests--; bytes -= operation.bytes();
            admission.notifyAll();
        }
        operation.payload = null;
        operation.path = null;
        if (failure == null) operation.completion.complete(result);
        else operation.completion.completeExceptionally(failure);
    }

    private void run(RingFactory factory) {
        Ring ring = null;
        IOException failure = null;
        var batch = new ArrayList<Operation>(options.entries());
        var active = new HashSet<Operation>();
        try {
            ring = factory.open(options);
            synchronized (admission) { activeRing = ring; }
            started.complete(null);
            while (true) {
                while (!queue.isEmpty() && (batch.size() < ring.available() || queue.peek().opcode == CLOSE)) {
                    if (!batch.isEmpty() && queue.peek().opcode == CLOSE) break;
                    batch.add(queue.remove());
                    if (batch.getFirst().opcode == CLOSE) break;
                }
                if (!batch.isEmpty() && batch.getFirst().opcode == CLOSE) {
                    Operation operation = batch.getFirst();
                    // Use close(2) after all prior CQEs. A failed asynchronous close
                    // could mean the syscall never ran, making retry/reuse ambiguous.
                    operation.file.closed = true;
                    files.remove(operation.file);
                    try { ring.closeFile(operation.file.fd); finish(operation, 0, null); }
                    catch (IOException problem) { finish(operation, null, problem); }
                    batch.clear();
                    continue;
                }
                boolean submitted = !batch.isEmpty();
                if (submitted) {
                    active.addAll(batch);
                    ring.submit(batch);
                    batch.clear();
                }
                List<Completion> ready = ring.poll(false);
                boolean progressed = submitted || !ready.isEmpty();
                complete(ready, active);
                // Drain submissions and completions until neither makes progress.
                // A completed caller may already have queued its next operation.
                if (progressed) continue;
                waiting.set(true);
                synchronized (admission) {
                    if (queue.isEmpty() && active.isEmpty() && !accepting) break;
                }
                // Publish WAIT before checking the queue, closing the lost-wakeup race.
                if (!queue.isEmpty() && (ring.available() > 0 || queue.peek().opcode == CLOSE)) {
                    waiting.set(false);
                    continue;
                }
                try { complete(ring.poll(true), active); }
                finally { waiting.set(false); }
            }
        } catch (Throwable problem) {
            failure = problem instanceof IOException io ? io : new IOException("io_uring owner failed", problem);
            synchronized (admission) { accepting = false; admission.notifyAll(); }
            active.addAll(batch);
            for (Operation operation : active) finish(operation, null, failure);
            batch.clear();
            synchronized (admission) { batch.addAll(queue); queue.clear(); }
            for (Operation operation : batch) finish(operation, null, failure);
        } finally {
            synchronized (admission) { accepting = false; admission.notifyAll(); }
            if (ring != null) {
                for (File file : files) {
                    try { ring.closeFile(file.fd); }
                    catch (Throwable cleanup) { failure = combine(failure, cleanupFailure(cleanup)); }
                    file.closed = true;
                }
                files.clear();
                try { ring.close(); } catch (Throwable cleanup) { failure = combine(failure, cleanupFailure(cleanup)); }
            }
            if (failure == null) stopped.complete(null);
            else stopped.completeExceptionally(failure);
            if (!started.isDone()) started.completeExceptionally(failure == null ? new IOException("Owner did not start") : failure);
        }
    }
    private void complete(List<Completion> completions, Set<Operation> active) throws IOException {
        for (Completion completion : completions) {
            if (!active.remove(completion.operation())) throw new IOException("Completion without an active request");
            finish(completion.operation(), completion.result(), null);
        }
    }
    private static IOException combine(IOException original, IOException next) {
        if (original == null) return next;
        if (original != next) original.addSuppressed(next);
        return original;
    }
    private static IOException cleanupFailure(Throwable failure) {
        return failure instanceof IOException io ? io : new IOException("io_uring resource cleanup failed", failure);
    }
    private static <T> T await(CompletableFuture<T> future) throws IOException {
        boolean interrupted = false;
        try {
            while (true) {
                try { return future.get(); }
                catch (InterruptedException ignored) { interrupted = true; }
                catch (ExecutionException failure) {
                    if (failure.getCause() instanceof IOException io) throw io;
                    throw new IOException(failure.getCause());
                }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    @Override public void close() throws IOException {
        synchronized (admission) { accepting = false; admission.notifyAll(); }
        IOException failure = null;
        try { wakeup(); } catch (IOException problem) { failure = problem; }
        try { await(stopped); } catch (IOException problem) { failure = combine(failure, problem); }
        boolean interrupted = false;
        while (worker.isAlive()) {
            try { worker.join(); } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure != null) throw failure;
    }

    /** File handles belong to their executor; closing either drains admitted operations. */
    public static final class File implements AutoCloseable {
        private final UringFileIo owner;
        // Per-file serialization makes a command reusable only after its CQE is delivered.
        private final Operation operation = new Operation(0, this, null, 0, false, null);
        int fd = -1;
        private volatile boolean closed;
        private IOException failed;
        private File(UringFileIo owner) { this.owner = owner; }
        private void ensureOpen() throws IOException {
            if (closed) throw new IOException("io_uring file is closed");
            if (failed != null) throw new IOException("io_uring file failed; close and reopen it", failed);
        }
        /**
         * Advances source.position() after completed writes. The caller must not modify
         * the source until this method returns. Buffers from allocateBuffer are
         * retained through completion; other heap/native buffers are copied.
         * Accepted work completes despite interruption, then restores interrupt status.
         */
        public synchronized void writeFully(ByteBuffer source, long position) throws IOException {
            ensureOpen();
            if (position < 0 || position > Long.MAX_VALUE - source.remaining()) throw new IllegalArgumentException("Invalid write range");
            try {
                while (source.hasRemaining()) {
                    int count = Math.min(source.remaining(), owner.options.maxWriteBytes());
                    ByteBuffer part = source.slice(source.position(), count);
                    // A caller can close an arena after an exceptional return. Copy
                    // scoped memory here, also permitting caller-confined arenas.
                    if (part.isDirect() && !ownsBuffer(MemorySegment.ofBuffer(part))) {
                        byte[] copy = new byte[count];
                        part.get(copy);
                        part = ByteBuffer.wrap(copy);
                    }
                    int written = owner.perform(operation.reset(WRITE, part, position, false, null), false);
                    if (written <= 0 || written > count) throw new IOException("Invalid write completion: " + written);
                    source.position(source.position() + written);
                    position += written;
                }
            } catch (IOException failure) { failed = failure; throw failure; }
        }
        /** Full fsync when metadata=true, otherwise fdatasync. Both wait for the CQE. */
        public synchronized void force(boolean metadata) throws IOException {
            ensureOpen();
            try {
                if (owner.perform(operation.reset(FSYNC, null, 0, metadata, null), false) != 0)
                    throw new IOException("Invalid fsync completion");
            } catch (IOException failure) { failed = failure; throw failure; }
        }
        @Override public synchronized void close() throws IOException {
            if (closed) return;
            owner.perform(operation.reset(CLOSE, null, 0, false, null), true);
        }
    }
}
