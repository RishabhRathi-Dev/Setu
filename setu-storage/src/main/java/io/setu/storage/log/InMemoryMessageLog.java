package io.setu.storage.log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lock-free, append-only partition log. A CAS publishes an entire batch and its
 * offsets together. Immutable chunks and a persistent AVL index let fetches use
 * one consistent snapshot while append, reclamation, or close proceeds.
 */
public final class InMemoryMessageLog implements MessageLog {
    private static final Logger LOG = LoggerFactory.getLogger(InMemoryMessageLog.class);
    private static final int MAX_RECORDS_PER_READ = 10_000;
    private static final int CHUNK_RECORDS = 1024;
    private final int maxRecordBytes;
    // Conservative reference-array, immutable index-node, and chunk-object allowance.
    private final long chunkMemoryBytes;
    private final boolean trackRetention;
    private final MemoryLogBudget budget;
    private final AtomicReference<MemoryLogState> state = new AtomicReference<>(new MemoryLogState(null, null, 0, 0, false));

    public InMemoryMessageLog(int maxRecordBytes) { this(maxRecordBytes, new MemoryLogBudget(0)); }
    public InMemoryMessageLog(int maxRecordBytes, MemoryLogBudget budget) { this(maxRecordBytes, budget, false); }

    public InMemoryMessageLog(int maxRecordBytes, MemoryLogBudget budget, boolean trackRetention) {
        if (maxRecordBytes < 0) throw new IllegalArgumentException("maxRecordBytes must be non-negative");
        this.maxRecordBytes = maxRecordBytes;
        this.budget = Objects.requireNonNull(budget, "budget");
        this.trackRetention = trackRetention;
        chunkMemoryBytes = CHUNK_RECORDS * (long) Long.BYTES + 192
                + (trackRetention ? CHUNK_RECORDS * (long) Long.BYTES + 24 : 0);
    }

    @Override
    public long append(byte[] value) throws IOException {
        ensureOpen(state.get());
        Objects.requireNonNull(value, "value");
        validateLength(value.length);
        return appendValues(List.of(MessageValue.copyOf(value)));
    }

    @Override
    public long appendBatch(List<byte[]> values) throws IOException {
        ensureOpen(state.get());
        Objects.requireNonNull(values, "values");
        for (byte[] value : values) validateLength(Objects.requireNonNull(value, "value").length);
        return appendValues(values.stream().map(MessageValue::copyOf).toList());
    }

    @Override
    public long appendValues(List<MessageValue> values) throws IOException {
        ensureOpen(state.get());
        List<MessageValue> owned = List.copyOf(values);
        if (owned.isEmpty()) throw new IllegalArgumentException("values must not be empty");
        long payloadBytes = 0;
        MessageValue previous = null;
        for (MessageValue value : owned) {
            validateLength(value.length());
            payloadBytes = Math.addExact(payloadBytes, recordMemoryBytes(value, previous));
            previous = value;
        }
        while (true) {
            MemoryLogState before = state.get();
            ensureOpen(before);
            long next = Math.addExact(before.nextOffset(), owned.size());
            int available = before.active() == null ? 0 : CHUNK_RECORDS - before.active().count();
            long chunks = (Math.max(0L, owned.size() - (long) available) + CHUNK_RECORDS - 1) / CHUNK_RECORDS;
            // Charge a shared frame once per contiguous run within each chunk. A frame
            // spanning chunks is deliberately charged in each, so either can be reclaimed.
            long chargedPayload = payloadBytes;
            if (available > 0 && owned.getFirst().sharesFrameWith(before.active().records()[before.active().count() - 1]))
                chargedPayload -= owned.getFirst().retainedBackingBytes();
            for (long boundary = available > 0 ? available : CHUNK_RECORDS;
                 boundary < owned.size(); boundary += CHUNK_RECORDS) {
                MessageValue value = owned.get((int) boundary);
                if (value.sharesFrameWith(owned.get((int) boundary - 1)))
                    chargedPayload = Math.addExact(chargedPayload, value.retainedBackingBytes());
            }
            long reservation = Math.addExact(chargedPayload, chunks * chunkMemoryBytes);
            budget.reserve(reservation);
            boolean published = false;
            try {
                MemoryLogNode root = before.root();
                MemoryLogChunk active = before.active();
                int position = 0;
                long offset = before.nextOffset();
                long now = trackRetention ? System.nanoTime() : 0;
                while (position < owned.size()) {
                    boolean extend = active != null && active.count() < CHUNK_RECORDS;
                    long base = extend ? active.base() : offset;
                    MessageValue[] records = extend ? active.records().clone() : new MessageValue[CHUNK_RECORDS];
                    long[] times = !trackRetention ? null : extend ? active.appendedNanos().clone() : new long[CHUNK_RECORDS];
                    int count = extend ? active.count() : 0;
                    int discarded = extend ? active.discarded() : 0;
                    long bytes = extend ? active.payloadBytes() : 0;
                    int take = Math.min(CHUNK_RECORDS - count, owned.size() - position);
                    for (int i = 0; i < take; i++) {
                        MessageValue value = owned.get(position++);
                        bytes += recordMemoryBytes(value, count == 0 ? null : records[count - 1]);
                        records[count] = value;
                        if (trackRetention) times[count] = now;
                        count++;
                    }
                    active = new MemoryLogChunk(base, records, times, count, discarded, bytes, chunkMemoryBytes);
                    root = put(root, active);
                    offset += take;
                }
                MemoryLogState after = new MemoryLogState(root, active, before.firstOffset(), next, false);
                if (state.compareAndSet(before, after)) {
                    published = true;
                    if (LOG.isDebugEnabled()) LOG.debug("Memory batch appended first_offset={} records={} next_offset={} retained_bytes={} shared_budget_bytes={}",
                            before.nextOffset(), owned.size(), next, bytes(root), budget.usedBytes());
                    return before.nextOffset();
                }
            } finally { if (!published) budget.release(reservation); }
        }
    }

    @Override
    public List<LogRecord> read(long firstOffset, int maxBytes) throws IOException {
        return read(firstOffset, maxBytes, ReadAdmission.UNLIMITED);
    }

    @Override
    public List<LogRecord> read(long firstOffset, int maxBytes, ReadAdmission admission) throws IOException {
        MemoryLogState snapshot = state.get();
        ensureOpen(snapshot);
        if (firstOffset < 0 || maxBytes < 0)
            throw new IllegalArgumentException("offset and maxBytes must be non-negative");
        if (maxBytes == 0) return List.of();
        long start = Math.max(firstOffset, snapshot.firstOffset());
        if (start >= snapshot.nextOffset()) return List.of();
        List<LogRecord> result = new ArrayList<>();
        long bytesRead = 0;
        long retainedFrames = 0;
        MessageValue previous = null;
        MemoryLogCursor cursor = new MemoryLogCursor(snapshot.root(), start);
        MemoryLogChunk chunk;
        while ((chunk = cursor.next()) != null) {
            int from = (int) Math.max(chunk.discarded(), start - chunk.base());
            int end = from;
            while (end < chunk.count() && result.size() + end - from < MAX_RECORDS_PER_READ) {
                MessageValue value = chunk.records()[end];
                int length = value.length();
                if (bytesRead + length > maxBytes) break;
                bytesRead += length;
                if (!value.sharesFrameWith(previous)) retainedFrames += value.retainedFrameBytes();
                previous = value;
                end++;
            }
            // Reserve the complete chunk before allocating any result records. The snapshot is
            // immutable, so sizing and materialization cannot disagree under append/reclamation.
            if (end > from) admission.reserve(bytesRead + retainedFrames + 64L * (result.size() + end - from) + 22);
            for (int i = from; i < end; i++) result.add(new LogRecord(chunk.base() + i, chunk.records()[i]));
            if (end < chunk.count() || result.size() == MAX_RECORDS_PER_READ) return List.copyOf(result);
        }
        return List.copyOf(result);
    }

    @Override
    public long nextOffset() { return state.get().nextOffset(); }
    @Override
    public long firstOffset() { return state.get().firstOffset(); }

    @Override
    public void discardBefore(long offset) throws IOException {
        while (true) {
            MemoryLogState before = state.get();
            ensureOpen(before);
            if (offset < 0 || offset > before.nextOffset())
                throw new IllegalArgumentException("offset must be between first and next offset");
            long target = Math.max(before.firstOffset(), offset);
            if (target == before.firstOffset()) return;
            if (reclaim(before, target)) return;
        }
    }

    /** Optional lossy retention; each record has its batch-append monotonic timestamp. */
    public void discardExpired(long now, long retentionNanos) throws IOException {
        if (retentionNanos <= 0 || !trackRetention) return;
        while (true) {
            MemoryLogState before = state.get();
            ensureOpen(before);
            long target = before.firstOffset();
            MemoryLogCursor cursor = new MemoryLogCursor(before.root(), target);
            MemoryLogChunk chunk;
            boolean found = false;
            while (!found && (chunk = cursor.next()) != null) {
                for (int i = chunk.discarded(); i < chunk.count(); i++) {
                    if (now - chunk.appendedNanos()[i] < retentionNanos) { found = true; break; }
                    target = chunk.base() + i + 1;
                }
            }
            if (target == before.firstOffset() || reclaim(before, target)) return;
        }
    }

    private boolean reclaim(MemoryLogState before, long target) {
        MemoryLogNode root = discardPrefix(before.root(), target);
        MemoryLogChunk active = last(root);
        MemoryLogState after = new MemoryLogState(root, active, target, before.nextOffset(), false);
        if (!state.compareAndSet(before, after)) return false;
        long released = bytes(before.root()) - bytes(root);
        budget.release(released);
        if (LOG.isDebugEnabled()) LOG.debug("Memory records discarded first_offset={} next_offset={} released_bytes={} retained_bytes={} shared_budget_bytes={}",
                target, before.nextOffset(), released, bytes(root), budget.usedBytes());
        return true;
    }

    static long recordMemoryBytes(MessageValue value, MessageValue previous) {
        return 32 + (value.sharesFrameWith(previous) ? 0 : value.retainedBackingBytes());
    }
    private void validateLength(int length) throws IOException {
        if (length > maxRecordBytes) throw new IOException("Record size " + length + " exceeds maximum " + maxRecordBytes);
    }
    private static void ensureOpen(MemoryLogState snapshot) throws IOException {
        if (snapshot.closed()) throw new IOException("Message log is closed");
    }

    @Override
    public void close() {
        while (true) {
            MemoryLogState before = state.get();
            if (before.closed()) return;
            if (state.compareAndSet(before, new MemoryLogState(null, null, before.nextOffset(), before.nextOffset(), true))) {
                budget.release(bytes(before.root()));
                return;
            }
        }
    }

    static int height(MemoryLogNode node) { return node == null ? 0 : node.height; }
    static long bytes(MemoryLogNode node) { return node == null ? 0 : node.bytes; }
    private static MemoryLogChunk last(MemoryLogNode node) {
        if (node == null) return null;
        while (node.right != null) node = node.right;
        return node.chunk;
    }
    private static MemoryLogNode put(MemoryLogNode node, MemoryLogChunk chunk) {
        if (node == null) return new MemoryLogNode(chunk, null, null);
        if (chunk.base() == node.chunk.base()) return new MemoryLogNode(chunk, node.left, node.right);
        if (chunk.base() < node.chunk.base()) return balance(node.chunk, put(node.left, chunk), node.right);
        return balance(node.chunk, node.left, put(node.right, chunk));
    }
    private static MemoryLogNode discardPrefix(MemoryLogNode node, long target) {
        if (node == null) return null;
        MemoryLogChunk chunk = node.chunk;
        if (chunk.base() + chunk.count() <= target) return discardPrefix(node.right, target);
        if (chunk.base() < target) return join(chunk.discard(target), null, node.right);
        MemoryLogNode left = discardPrefix(node.left, target);
        return left == node.left ? node : join(chunk, left, node.right);
    }
    // Prefix deletion can remove an arbitrarily tall subtree; join restores AVL
    // balance along its boundary without copying untouched chunks or subtrees.
    private static MemoryLogNode join(MemoryLogChunk chunk, MemoryLogNode left, MemoryLogNode right) {
        if (height(left) > height(right) + 1)
            return balance(left.chunk, left.left, join(chunk, left.right, right));
        if (height(right) > height(left) + 1)
            return balance(right.chunk, join(chunk, left, right.left), right.right);
        return new MemoryLogNode(chunk, left, right);
    }
    private static MemoryLogNode balance(MemoryLogChunk chunk, MemoryLogNode left, MemoryLogNode right) {
        if (height(left) > height(right) + 1) {
            if (height(left.left) >= height(left.right))
                return new MemoryLogNode(left.chunk, left.left, new MemoryLogNode(chunk, left.right, right));
            MemoryLogNode pivot = left.right;
            return new MemoryLogNode(pivot.chunk, new MemoryLogNode(left.chunk, left.left, pivot.left), new MemoryLogNode(chunk, pivot.right, right));
        }
        if (height(right) > height(left) + 1) {
            if (height(right.right) >= height(right.left))
                return new MemoryLogNode(right.chunk, new MemoryLogNode(chunk, left, right.left), right.right);
            MemoryLogNode pivot = right.left;
            return new MemoryLogNode(pivot.chunk, new MemoryLogNode(chunk, left, pivot.left), new MemoryLogNode(right.chunk, pivot.right, right.right));
        }
        return new MemoryLogNode(chunk, left, right);
    }
}
