package io.setu.storage.log;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
    private final AtomicReference<State> state = new AtomicReference<>(new State(null, null, 0, 0, false));

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
        for (MessageValue value : owned) {
            validateLength(value.length());
            payloadBytes = Math.addExact(payloadBytes, recordMemoryBytes(value));
        }
        while (true) {
            State before = state.get();
            ensureOpen(before);
            long next = Math.addExact(before.nextOffset, owned.size());
            int available = before.active == null ? 0 : CHUNK_RECORDS - before.active.count;
            long chunks = (Math.max(0L, owned.size() - (long) available) + CHUNK_RECORDS - 1) / CHUNK_RECORDS;
            long reservation = Math.addExact(payloadBytes, chunks * chunkMemoryBytes);
            budget.reserve(reservation);
            boolean published = false;
            try {
                Node root = before.root;
                Chunk active = before.active;
                int position = 0;
                long offset = before.nextOffset;
                long now = trackRetention ? System.nanoTime() : 0;
                while (position < owned.size()) {
                    boolean extend = active != null && active.count < CHUNK_RECORDS;
                    long base = extend ? active.base : offset;
                    MessageValue[] records = extend ? active.records.clone() : new MessageValue[CHUNK_RECORDS];
                    long[] times = !trackRetention ? null : extend ? active.appendedNanos.clone() : new long[CHUNK_RECORDS];
                    int count = extend ? active.count : 0;
                    int discarded = extend ? active.discarded : 0;
                    long bytes = extend ? active.payloadBytes : 0;
                    int take = Math.min(CHUNK_RECORDS - count, owned.size() - position);
                    for (int i = 0; i < take; i++) {
                        MessageValue value = owned.get(position++);
                        records[count] = value;
                        if (trackRetention) times[count] = now;
                        count++;
                        bytes += recordMemoryBytes(value);
                    }
                    active = new Chunk(base, records, times, count, discarded, bytes, chunkMemoryBytes);
                    root = put(root, active);
                    offset += take;
                }
                State after = new State(root, active, before.firstOffset, next, false);
                if (state.compareAndSet(before, after)) {
                    published = true;
                    if (LOG.isDebugEnabled()) LOG.debug("Memory batch appended first_offset={} records={} next_offset={} retained_bytes={} shared_budget_bytes={}",
                            before.nextOffset, owned.size(), next, bytes(root), budget.usedBytes());
                    return before.nextOffset;
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
        State snapshot = state.get();
        ensureOpen(snapshot);
        if (firstOffset < 0 || maxBytes < 0)
            throw new IllegalArgumentException("offset and maxBytes must be non-negative");
        if (maxBytes == 0) return List.of();
        long start = Math.max(firstOffset, snapshot.firstOffset);
        if (start >= snapshot.nextOffset) return List.of();
        List<LogRecord> result = new ArrayList<>();
        long bytesRead = 0;
        Cursor cursor = new Cursor(snapshot.root, start);
        Chunk chunk;
        while ((chunk = cursor.next()) != null) {
            int from = (int) Math.max(chunk.discarded, start - chunk.base);
            int end = from;
            while (end < chunk.count && result.size() + end - from < MAX_RECORDS_PER_READ) {
                int length = chunk.records[end].length();
                if (bytesRead + length > maxBytes) break;
                bytesRead += length;
                end++;
            }
            // Reserve the complete chunk before allocating any result records. The snapshot is
            // immutable, so sizing and materialization cannot disagree under append/reclamation.
            if (end > from) admission.reserve(bytesRead + 64L * (result.size() + end - from) + 22);
            for (int i = from; i < end; i++) result.add(new LogRecord(chunk.base + i, chunk.records[i]));
            if (end < chunk.count || result.size() == MAX_RECORDS_PER_READ) return List.copyOf(result);
        }
        return List.copyOf(result);
    }

    @Override
    public long nextOffset() { return state.get().nextOffset; }
    @Override
    public long firstOffset() { return state.get().firstOffset; }

    @Override
    public void discardBefore(long offset) throws IOException {
        while (true) {
            State before = state.get();
            ensureOpen(before);
            if (offset < 0 || offset > before.nextOffset)
                throw new IllegalArgumentException("offset must be between first and next offset");
            long target = Math.max(before.firstOffset, offset);
            if (target == before.firstOffset) return;
            if (reclaim(before, target)) return;
        }
    }

    /** Optional lossy retention; each record has its batch-append monotonic timestamp. */
    public void discardExpired(long now, long retentionNanos) throws IOException {
        if (retentionNanos <= 0 || !trackRetention) return;
        while (true) {
            State before = state.get();
            ensureOpen(before);
            long target = before.firstOffset;
            Cursor cursor = new Cursor(before.root, target);
            Chunk chunk;
            boolean found = false;
            while (!found && (chunk = cursor.next()) != null) {
                for (int i = chunk.discarded; i < chunk.count; i++) {
                    if (now - chunk.appendedNanos[i] < retentionNanos) { found = true; break; }
                    target = chunk.base + i + 1;
                }
            }
            if (target == before.firstOffset || reclaim(before, target)) return;
        }
    }

    private boolean reclaim(State before, long target) {
        Node root = discardPrefix(before.root, target);
        Chunk active = last(root);
        State after = new State(root, active, target, before.nextOffset, false);
        if (!state.compareAndSet(before, after)) return false;
        long released = bytes(before.root) - bytes(root);
        budget.release(released);
        if (LOG.isDebugEnabled()) LOG.debug("Memory records discarded first_offset={} next_offset={} released_bytes={} retained_bytes={} shared_budget_bytes={}",
                target, before.nextOffset, released, bytes(root), budget.usedBytes());
        return true;
    }

    private static long recordMemoryBytes(MessageValue value) { return ((value.retainedArrayLength() + 24L + 7) & ~7L) + 32; }
    private void validateLength(int length) throws IOException {
        if (length > maxRecordBytes) throw new IOException("Record size " + length + " exceeds maximum " + maxRecordBytes);
    }
    private static void ensureOpen(State snapshot) throws IOException {
        if (snapshot.closed) throw new IOException("Message log is closed");
    }

    @Override
    public void close() {
        while (true) {
            State before = state.get();
            if (before.closed) return;
            if (state.compareAndSet(before, new State(null, null, before.nextOffset, before.nextOffset, true))) {
                budget.release(bytes(before.root));
                return;
            }
        }
    }

    private record State(Node root, Chunk active, long firstOffset, long nextOffset, boolean closed) { }
    private record Chunk(long base, MessageValue[] records, long[] appendedNanos, int count,
                         int discarded, long payloadBytes, long overheadBytes) {
        long bytes() { return payloadBytes + overheadBytes; }
        Chunk discard(long target) {
            int removed = (int) (target - base);
            MessageValue[] retained = records.clone();
            long bytes = payloadBytes;
            for (int i = discarded; i < removed; i++) bytes -= recordMemoryBytes(retained[i]);
            Arrays.fill(retained, discarded, removed, null);
            // Timestamps contain no payload references and can remain shared.
            return new Chunk(base, retained, appendedNanos, count, removed, bytes, overheadBytes);
        }
    }

    private static final class Node {
        final Chunk chunk;
        final Node left, right;
        final int height;
        final long bytes;
        Node(Chunk chunk, Node left, Node right) {
            this.chunk = chunk; this.left = left; this.right = right;
            height = 1 + Math.max(height(left), height(right));
            bytes = chunk.bytes() + bytes(left) + bytes(right);
        }
    }
    private static int height(Node node) { return node == null ? 0 : node.height; }
    private static long bytes(Node node) { return node == null ? 0 : node.bytes; }
    private static Chunk last(Node node) {
        if (node == null) return null;
        while (node.right != null) node = node.right;
        return node.chunk;
    }
    private static Node put(Node node, Chunk chunk) {
        if (node == null) return new Node(chunk, null, null);
        if (chunk.base == node.chunk.base) return new Node(chunk, node.left, node.right);
        if (chunk.base < node.chunk.base) return balance(node.chunk, put(node.left, chunk), node.right);
        return balance(node.chunk, node.left, put(node.right, chunk));
    }
    private static Node discardPrefix(Node node, long target) {
        if (node == null) return null;
        Chunk chunk = node.chunk;
        if (chunk.base + chunk.count <= target) return discardPrefix(node.right, target);
        if (chunk.base < target) return join(chunk.discard(target), null, node.right);
        Node left = discardPrefix(node.left, target);
        return left == node.left ? node : join(chunk, left, node.right);
    }
    // Prefix deletion can remove an arbitrarily tall subtree; join restores AVL
    // balance along its boundary without copying untouched chunks or subtrees.
    private static Node join(Chunk chunk, Node left, Node right) {
        if (height(left) > height(right) + 1)
            return balance(left.chunk, left.left, join(chunk, left.right, right));
        if (height(right) > height(left) + 1)
            return balance(right.chunk, join(chunk, left, right.left), right.right);
        return new Node(chunk, left, right);
    }
    private static Node balance(Chunk chunk, Node left, Node right) {
        if (height(left) > height(right) + 1) {
            if (height(left.left) >= height(left.right))
                return new Node(left.chunk, left.left, new Node(chunk, left.right, right));
            Node pivot = left.right;
            return new Node(pivot.chunk, new Node(left.chunk, left.left, pivot.left), new Node(chunk, pivot.right, right));
        }
        if (height(right) > height(left) + 1) {
            if (height(right.right) >= height(right.left))
                return new Node(right.chunk, new Node(chunk, left, right.left), right.right);
            Node pivot = right.left;
            return new Node(pivot.chunk, new Node(chunk, left, pivot.left), new Node(right.chunk, pivot.right, right.right));
        }
        return new Node(chunk, left, right);
    }
    private static final class Cursor {
        final ArrayDeque<Node> path = new ArrayDeque<>();
        Cursor(Node node, long start) {
            while (node != null) {
                if (node.chunk.base + node.chunk.count <= start) node = node.right;
                else { path.push(node); node = node.left; }
            }
        }
        Chunk next() {
            if (path.isEmpty()) return null;
            Node next = path.pop();
            Node node = next.right;
            while (node != null) { path.push(node); node = node.left; }
            return next.chunk;
        }
    }
}
