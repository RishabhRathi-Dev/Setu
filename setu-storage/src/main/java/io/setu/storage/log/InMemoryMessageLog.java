package io.setu.storage.log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Arrays;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Append-only partition log retained only for the lifetime of this instance. */
public final class InMemoryMessageLog implements MessageLog {
    private static final Logger LOG = LoggerFactory.getLogger(InMemoryMessageLog.class);
    private static final int MAX_RECORDS_PER_READ = 10_000;
    private final int maxRecordBytes;
    private static final int CHUNK_RECORDS = 1024;
    // Conservative reference-array, map-entry, key, and chunk-object allowance.
    private final long chunkMemoryBytes;
    private final boolean trackRetention;
    private final MemoryLogBudget budget;
    private long retainedBytes;
    private final NavigableMap<Long, Chunk> chunks = new TreeMap<>();
    private Chunk active;
    private long firstOffset;
    private long nextOffset;
    private boolean closed;

    public InMemoryMessageLog(int maxRecordBytes) {
        this(maxRecordBytes, new MemoryLogBudget(0));
    }

    public InMemoryMessageLog(int maxRecordBytes, MemoryLogBudget budget) {
        this(maxRecordBytes, budget, false);
    }

    public InMemoryMessageLog(int maxRecordBytes, MemoryLogBudget budget, boolean trackRetention) {
        if (maxRecordBytes < 0) throw new IllegalArgumentException("maxRecordBytes must be non-negative");
        this.maxRecordBytes = maxRecordBytes;
        this.budget = Objects.requireNonNull(budget, "budget");
        this.trackRetention = trackRetention;
        this.chunkMemoryBytes = CHUNK_RECORDS * (long) Long.BYTES + 128
                + (trackRetention ? CHUNK_RECORDS * (long) Long.BYTES + 24 : 0);
    }

    @Override
    public synchronized long append(byte[] value) throws IOException {
        ensureOpen();
        Objects.requireNonNull(value, "value");
        if (value.length > maxRecordBytes)
            throw new IOException("Record size " + value.length + " exceeds maximum " + maxRecordBytes);
        long reservation = recordMemoryBytes(value) + chunkReservation(1);
        budget.reserve(reservation);
        long before = retainedBytes;
        try { return appendValidated(value); }
        finally { budget.release(reservation - (retainedBytes - before)); }
    }

    @Override
    public synchronized long appendBatch(List<byte[]> values) throws IOException {
        return appendValues(values.stream().map(MessageValue::copyOf).toList());
    }

    @Override
    public synchronized long appendValues(List<MessageValue> values) throws IOException {
        ensureOpen();
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) throw new IllegalArgumentException("values must not be empty");
        long first = nextOffset;
        long reservation = chunkReservation(values.size());
        for (MessageValue value : values) {
            Objects.requireNonNull(value, "value");
            if (value.length() > maxRecordBytes)
                throw new IOException("Record size " + value.length() + " exceeds maximum " + maxRecordBytes);
            reservation += recordMemoryBytes(value);
        }
        budget.reserve(reservation);
        long before = retainedBytes;
        try {
            for (MessageValue value : values) appendValidated(value);
            if (LOG.isDebugEnabled()) LOG.debug("Memory batch appended first_offset={} records={} next_offset={} retained_bytes={} shared_budget_bytes={}",
                    first, values.size(), nextOffset, retainedBytes, budget.usedBytes());
            return first;
        } finally { budget.release(reservation - (retainedBytes - before)); }
    }

    @Override
    public synchronized List<LogRecord> read(long firstOffset, int maxBytes) throws IOException {
        return read(firstOffset, maxBytes, ReadAdmission.UNLIMITED);
    }

    @Override
    public synchronized List<LogRecord> read(long firstOffset, int maxBytes, ReadAdmission admission) throws IOException {
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
                MessageValue value = chunk.records[i];
                int length = value.length();
                if (bytesRead + length > maxBytes || result.size() == MAX_RECORDS_PER_READ)
                    return List.copyOf(result);
                admission.reserve(bytesRead + length + 64L * (result.size() + 1) + 22);
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
        long releasedBytes = 0;
        while (!chunks.isEmpty()) {
            var first = chunks.firstEntry();
            if (first.getKey() + first.getValue().count > target) break;
            releasedBytes += chunkMemoryBytes + first.getValue().payloadBytes;
            chunks.pollFirstEntry();
        }
        if (chunks.isEmpty()) active = null;
        else {
            var first = chunks.firstEntry();
            Chunk chunk = first.getValue();
            int removed = (int) (target - first.getKey());
            for (int i = chunk.discarded; i < removed; i++) {
                long bytes = recordMemoryBytes(chunk.records[i]);
                releasedBytes += bytes;
                chunk.payloadBytes -= bytes;
            }
            Arrays.fill(chunk.records, chunk.discarded, removed, null);
            chunk.discarded = removed;
        }
        firstOffset = target;
        retainedBytes -= releasedBytes;
        budget.release(releasedBytes);
        if (releasedBytes > 0 && LOG.isDebugEnabled()) LOG.debug("Memory records discarded first_offset={} next_offset={} released_bytes={} retained_bytes={} shared_budget_bytes={}",
                firstOffset, nextOffset, releasedBytes, retainedBytes, budget.usedBytes());
    }

    /** Optional lossy retention; timestamps use a monotonic clock and expire individual records. */
    public synchronized void discardExpired(long now, long retentionNanos) throws IOException {
        if (retentionNanos <= 0 || !trackRetention) return;
        long target = firstOffset;
        for (var entry : chunks.entrySet()) {
            Chunk chunk = entry.getValue();
            for (int i = chunk.discarded; i < chunk.count; i++) {
                if (now - chunk.appendedNanos[i] < retentionNanos) { discardBefore(target); return; }
                target = entry.getKey() + i + 1;
            }
        }
        discardBefore(target);
    }

    private long chunkReservation(int count) {
        int available = active == null ? 0 : CHUNK_RECORDS - active.count;
        long newChunks = (Math.max(0L, count - (long) available) + CHUNK_RECORDS - 1) / CHUNK_RECORDS;
        return newChunks * chunkMemoryBytes;
    }

    private static long recordMemoryBytes(byte[] value) {
        // Array allowance plus the immutable payload wrapper.
        return ((value.length + 24L + 7) & ~7L) + 24;
    }

    private static long recordMemoryBytes(MessageValue value) {
        return ((value.length() + 24L + 7) & ~7L) + 24;
    }

    private long appendValidated(byte[] value) {
        return appendValidated(MessageValue.copyOf(value));
    }

    private long appendValidated(MessageValue copy) {
        if (active == null || active.count == CHUNK_RECORDS) {
            Chunk created = new Chunk(trackRetention);
            chunks.put(nextOffset, created);
            active = created;
            retainedBytes += chunkMemoryBytes;
        }
        if (trackRetention) active.appendedNanos[active.count] = System.nanoTime();
        active.records[active.count++] = copy;
        long bytes = recordMemoryBytes(copy);
        active.payloadBytes += bytes;
        retainedBytes += bytes;
        return nextOffset++;
    }

    private static final class Chunk {
        private final MessageValue[] records = new MessageValue[CHUNK_RECORDS];
        private final long[] appendedNanos;
        private Chunk(boolean retention) { appendedNanos = retention ? new long[CHUNK_RECORDS] : null; }
        private int count;
        private int discarded;
        private long payloadBytes;
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
        budget.release(retainedBytes);
        retainedBytes = 0;
    }
}
