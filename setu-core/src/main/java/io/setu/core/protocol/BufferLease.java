package io.setu.core.protocol;

import java.io.IOException;

public final class BufferLease implements AutoCloseable {
    private final BufferBudget budget;
    private long bytes;
    private boolean released;
    BufferLease(BufferBudget budget, long bytes) { this.budget = budget; this.bytes = bytes; }
    public synchronized void expand(long additional) throws IOException {
        if (released) throw new IOException("Buffer reservation was cancelled");
        budget.acquire(additional);
        bytes += additional;
    }
    public synchronized void close() { if (!released) { released = true; budget.release(bytes); } }
}
