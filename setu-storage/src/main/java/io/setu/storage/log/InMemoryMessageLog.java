package io.setu.storage.log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Append-only partition log retained only for the lifetime of this instance. */
public final class InMemoryMessageLog implements MessageLog {
    private static final int MAX_RECORDS_PER_READ = 10_000;
    private final int maxRecordBytes;
    private final List<byte[]> records = new ArrayList<>();
    private long firstOffset;
    private long nextOffset;
    private boolean closed;

    public InMemoryMessageLog(int maxRecordBytes) {
        if (maxRecordBytes < 0) throw new IllegalArgumentException("maxRecordBytes must be non-negative");
        this.maxRecordBytes = maxRecordBytes;
    }

    @Override
    public synchronized long append(byte[] value) throws IOException {
        ensureOpen();
        Objects.requireNonNull(value, "value");
        if (value.length > maxRecordBytes)
            throw new IOException("Record size " + value.length + " exceeds maximum " + maxRecordBytes);
        long offset = nextOffset++;
        records.add(value.clone());
        return offset;
    }

    @Override
    public synchronized List<LogRecord> read(long firstOffset, int maxBytes) throws IOException {
        ensureOpen();
        if (firstOffset < 0 || maxBytes < 0)
            throw new IllegalArgumentException("offset and maxBytes must be non-negative");
        if (maxBytes == 0) return List.of();
        List<LogRecord> result = new ArrayList<>();
        long bytesRead = 0;
        long start = Math.max(firstOffset, this.firstOffset);
        for (long offset = start; offset < nextOffset && result.size() < MAX_RECORDS_PER_READ; offset++) {
            byte[] value = records.get(Math.toIntExact(offset - this.firstOffset));
            if (bytesRead + value.length > maxBytes) break;
            result.add(new LogRecord(offset, value));
            bytesRead += value.length;
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized long nextOffset() {
        return nextOffset;
    }

    @Override
    public synchronized long firstOffset() {
        return firstOffset;
    }

    @Override
    public synchronized void discardBefore(long offset) throws IOException {
        ensureOpen();
        if (offset < 0 || offset > nextOffset)
            throw new IllegalArgumentException("offset must be between first and next offset");
        long target = Math.max(firstOffset, offset);
        int removeCount = Math.toIntExact(target - firstOffset);
        if (removeCount > 0) {
            records.subList(0, removeCount).clear();
            firstOffset = target;
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Message log is closed");
    }

    @Override
    public synchronized void close() {
        closed = true;
        records.clear();
        firstOffset = nextOffset;
    }
}
