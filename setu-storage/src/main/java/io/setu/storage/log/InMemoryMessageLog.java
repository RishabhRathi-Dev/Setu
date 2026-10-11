package io.setu.storage.log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Arrays;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Append-only partition log retained only for the lifetime of this instance. */
public final class InMemoryMessageLog implements MessageLog {
    private static final int MAX_RECORDS_PER_READ = 10_000;
    private final int maxRecordBytes;
    private static final int CHUNK_RECORDS = 1024;
    private final NavigableMap<Long, Chunk> chunks = new TreeMap<>();
    private Chunk active;
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
        return appendValidated(value);
    }

    @Override
    public synchronized long appendBatch(List<byte[]> values) throws IOException {
        ensureOpen();
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) throw new IllegalArgumentException("values must not be empty");
        long first = nextOffset;
        for (byte[] value : values) {
            Objects.requireNonNull(value, "value");
            if (value.length > maxRecordBytes)
                throw new IOException("Record size " + value.length + " exceeds maximum " + maxRecordBytes);
        }
        for (byte[] value : values) {
            appendValidated(value);
        }
        return first;
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
        if (start >= nextOffset) return List.of();
        long base = chunks.floorKey(start);
        for (var entry : chunks.tailMap(base, true).entrySet()) {
            Chunk chunk = entry.getValue();
            int from = (int) Math.max(0, start - entry.getKey());
            for (int i = from; i < chunk.count; i++) {
                byte[] value = chunk.records[i];
                int length = value.length;
                if (bytesRead + length > maxBytes || result.size() == MAX_RECORDS_PER_READ)
                    return List.copyOf(result);
                result.add(new LogRecord(entry.getKey() + i, value));
                bytesRead += length;
            }
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
        while (!chunks.isEmpty()) {
            var first = chunks.firstEntry();
            if (first.getKey() + first.getValue().count > target) break;
            chunks.pollFirstEntry();
        }
        if (chunks.isEmpty()) active = null;
        else {
            var first = chunks.firstEntry();
            Chunk chunk = first.getValue();
            int removed = (int) (target - first.getKey());
            Arrays.fill(chunk.records, chunk.discarded, removed, null);
            chunk.discarded = removed;
        }
        firstOffset = target;
    }

    private long appendValidated(byte[] value) {
        byte[] copy = value.clone();
        if (active == null || active.count == CHUNK_RECORDS) {
            active = new Chunk();
            chunks.put(nextOffset, active);
        }
        active.records[active.count++] = copy;
        return nextOffset++;
    }

    private static final class Chunk {
        private final byte[][] records = new byte[CHUNK_RECORDS][];
        private int count;
        private int discarded;
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Message log is closed");
    }

    @Override
    public synchronized void close() {
        closed = true;
        chunks.clear();
        active = null;
        firstOffset = nextOffset;
    }
}
