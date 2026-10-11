package io.setu.storage.log;

import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared admission budget for retained memory-log payloads and chunks, not total JVM heap. */
public final class MemoryLogBudget {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryLogBudget.class);
    private final long maximumBytes;
    private final AtomicLong usedBytes = new AtomicLong();

    /** Zero disables the limit while retaining accounting. */
    public MemoryLogBudget(long maximumBytes) {
        if (maximumBytes < 0) throw new IllegalArgumentException("maximumBytes must be non-negative");
        this.maximumBytes = maximumBytes;
    }

    public long usedBytes() { return usedBytes.get(); }

    void reserve(long bytes) throws LogCapacityExceededException {
        if (bytes < 0) throw new IllegalArgumentException("bytes must be non-negative");
        while (true) {
            long used = usedBytes.get();
            if (bytes > Long.MAX_VALUE - used || (maximumBytes > 0 && bytes > maximumBytes - used)) {
                LOG.warn("Memory log admission rejected used_bytes={} requested_bytes={} limit_bytes={}", used, bytes, maximumBytes);
                throw new LogCapacityExceededException("In-memory log budget exceeded: used=" + used
                        + ", requested=" + bytes + ", limit=" + maximumBytes
                        + "; acknowledge retained records, reduce the batch, or increase the budget");
            }
            if (usedBytes.compareAndSet(used, used + bytes)) return;
        }
    }

    void release(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("bytes must be non-negative");
        while (true) {
            long used = usedBytes.get();
            if (bytes > used) throw new IllegalStateException("Memory log budget accounting underflow");
            if (usedBytes.compareAndSet(used, used - bytes)) return;
        }
    }
}
