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

    long nextOffset();

    /** Lowest offset still retained; persistent implementations may always return zero. */
    default long firstOffset() { return 0; }

    /** Discard records strictly before the supplied offset when this backend supports reclamation. */
    default void discardBefore(long offset) throws IOException { }

    @Override
    void close() throws IOException;
}
