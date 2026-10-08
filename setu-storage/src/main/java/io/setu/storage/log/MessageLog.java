package io.setu.storage.log;

import java.io.IOException;
import java.util.List;

/** An append-only, zero-based message log for one partition. */
public interface MessageLog extends AutoCloseable {
    long append(byte[] value) throws IOException;

    List<LogRecord> read(long firstOffset, int maxBytes) throws IOException;

    long nextOffset();

    @Override
    void close() throws IOException;
}
