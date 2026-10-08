package io.setu.storage.log;

import java.io.IOException;
import java.util.List;

/** An append-only, zero-based message log for one partition. */
public interface MessageLog extends AutoCloseable {
    long append(byte[] value) throws IOException;

    List<LogRecord> read(long firstOffset, int maxBytes) throws IOException;

    long nextOffset();

    /** Lowest offset still retained; persistent implementations may always return zero. */
    default long firstOffset() { return 0; }

    /** Discard records strictly before the supplied offset when this backend supports reclamation. */
    default void discardBefore(long offset) throws IOException { }

    @Override
    void close() throws IOException;
}
