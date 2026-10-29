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
    public BufferLease reserve(long bytes) throws IOException {
        acquire(bytes);
        return new BufferLease(this, bytes);
    }
    void acquire(long bytes) throws IOException {
        if (bytes < 0) throw new IllegalArgumentException("negative reservation");
        while (true) {
            long current = used.get();
            if (bytes > maximum - current) throw new BufferBudgetExceededException("In-flight byte budget exceeded");
            if (used.compareAndSet(current, current + bytes)) return;
        }
    }

    void release(long bytes) { used.addAndGet(-bytes); }
}
