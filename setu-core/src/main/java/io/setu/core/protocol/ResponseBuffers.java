package io.setu.core.protocol;

import java.io.IOException;

/** Transport-owned response credit, held through socket write completion. */
public final class ResponseBuffers implements AutoCloseable {
    private static final ThreadLocal<ResponseBuffers> CURRENT = new ThreadLocal<>();
    private final BufferBudget budget;
    private BufferBudget.Lease lease;
    private long reserved;
    private boolean closed;
    public ResponseBuffers(BufferBudget budget) { this.budget = budget; }
    public static void reserve(long bytes) throws IOException {
        ResponseBuffers current = CURRENT.get();
        if (current != null) current.ensure(bytes);
    }
    public synchronized void ensure(long bytes) throws IOException {
        if (closed) throw new IOException("Response was cancelled");
        if (bytes > reserved) {
            long quantum = Math.max(1, Math.min(4096, budget.maximumBytes() / 128));
            long target = Math.addExact(bytes, quantum - 1) / quantum * quantum;
            if (bytes <= budget.maximumBytes()) target = Math.min(target, budget.maximumBytes());
            if (lease == null) lease = budget.reserve(target);
            else lease.expand(target - reserved);
            reserved = target;
        }
    }
    public AutoCloseable activate() {
        ResponseBuffers previous = CURRENT.get();
        CURRENT.set(this);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (lease != null) lease.close();
    }
}
