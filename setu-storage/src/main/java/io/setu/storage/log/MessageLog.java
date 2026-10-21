package io.setu.storage.log;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** An append-only, zero-based message log for one partition. */
public interface MessageLog extends AutoCloseable {
    long append(byte[] value) throws IOException;

    /** Appends a contiguous group and returns its first offset. Durable implementations may sync once per group. */
    default long appendBatch(List<byte[]> values) throws IOException {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) throw new IllegalArgumentException("values must not be empty");
        long first = -1;
        for (byte[] value : values) {
            long offset = append(value);
            if (first < 0) first = offset;
        }
        return first;
    }

    List<LogRecord> read(long firstOffset, int maxBytes) throws IOException;

    default List<LogRecord> read(long offset, int maxBytes, ReadAdmission admission) throws IOException {
        admission.reserve(3L * maxBytes + 640_000);
        return read(offset, maxBytes);
    }

    /** Immutable input avoids copy-on-read accessors between protocol and storage. */
    default long appendValues(List<MessageValue> values) throws IOException {
        return appendBatch(values.stream().map(MessageValue::copy).toList());
    }

    default java.util.concurrent.CompletableFuture<Long> appendValuesAsync(List<MessageValue> values) throws IOException {
        return java.util.concurrent.CompletableFuture.completedFuture(appendValues(values));
    }

    default void awaitPendingWrites() throws IOException { }

    long nextOffset();

    /** Lowest offset still retained. */
    default long firstOffset() { return 0; }

    /** Discard records strictly before the supplied offset when this backend supports reclamation. */
    default void discardBefore(long offset) throws IOException { }

    /** Apply optional age-based file retention; zero disables expiry. */
    default void discardExpiredFiles(long retentionMillis) throws IOException { }

    @Override
    void close() throws IOException;
}
