package io.setu.core.protocol;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/** Non-blocking byte admission; independent request/response budgets avoid credit deadlock. */
public final class BufferBudget {
    private final long maximum;
    private final AtomicLong used = new AtomicLong();
    public BufferBudget(long maximum) {
        if (maximum <= 0) throw new IllegalArgumentException("buffer budget must be positive");
        this.maximum = maximum;
    }
    public long usedBytes() { return used.get(); }
    public long maximumBytes() { return maximum; }
    public Lease reserve(long bytes) throws IOException {
        acquire(bytes);
        return new Lease(bytes);
    }
    private void acquire(long bytes) throws IOException {
        if (bytes < 0) throw new IllegalArgumentException("negative reservation");
        while (true) {
            long current = used.get();
            if (bytes > maximum - current) throw new Exhausted("In-flight byte budget exceeded");
            if (used.compareAndSet(current, current + bytes)) return;
        }
    }
    public static final class Exhausted extends IOException {
        public Exhausted(String message) { super(message); }
    }
    public final class Lease implements AutoCloseable {
        private long bytes;
        private boolean released;
        private Lease(long bytes) { this.bytes = bytes; }
        public synchronized void expand(long additional) throws IOException {
            if (released) throw new IOException("Buffer reservation was cancelled");
            acquire(additional);
            bytes += additional;
        }
        public synchronized void close() { if (!released) { released = true; used.addAndGet(-bytes); } }
    }
}
