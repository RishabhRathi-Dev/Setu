package io.setu.storage.log;

/** Per-partition outstanding FILE writes, including the active flush. Zero disables each admission limit. */
public record FileWriteQueueOptions(long maxBytes, int maxRequests) {
    public FileWriteQueueOptions {
        if (maxBytes < 0 || maxRequests < 0)
            throw new IllegalArgumentException("FILE write queue limits must be non-negative");
    }

    public static FileWriteQueueOptions defaults() {
        return new FileWriteQueueOptions(32L * 1024 * 1024, 1024);
    }

    public static FileWriteQueueOptions unlimited() {
        return new FileWriteQueueOptions(0, 0);
    }
}
