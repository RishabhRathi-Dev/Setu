package io.setu.storage.log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Bounded MPSC partition writer. Admission and publication use one CAS, so neither
 * shutdown nor a failed flush can miss an admitted request. The single writer
 * reverses each detached producer stack to preserve FIFO publication order.
 * Queue operations are lock-free; durability waits and file I/O remain blocking.
 */
public final class GroupCommitMessageLog implements MessageLog {
    private final java.util.concurrent.atomic.LongAdder flushNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder flushCount = new java.util.concurrent.atomic.LongAdder();
    private static final long TARGET_GROUP_BYTES = 256L * 1024;
    private final MessageLog delegate;
    private final long maximumBytes;
    private final int maximumRequests;
    private final long lingerNanos;
    private final long targetGroupBytes;
    // Counts include the detached group currently being flushed.
    private final AtomicReference<GroupCommitState> state = new AtomicReference<>(new GroupCommitState(null, 0, 0, false, null));
    private final AtomicBoolean closeDelegate = new AtomicBoolean();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final Thread writer;

    public GroupCommitMessageLog(MessageLog delegate, long maximumBytes, int maximumRequests, long lingerNanos) {
        if (maximumBytes <= 0 || maximumRequests <= 0 || lingerNanos < 0)
            throw new IllegalArgumentException("invalid group commit limits");
        this.delegate = java.util.Objects.requireNonNull(delegate);
        this.maximumBytes = maximumBytes;
        this.maximumRequests = maximumRequests;
        this.lingerNanos = lingerNanos;
        // A batch already large enough to amortize a force need not wait for
        // another synchronous producer. Keep lingering to coalesce small writes.
        this.targetGroupBytes = Math.max(1, Math.min(TARGET_GROUP_BYTES, maximumBytes / 2));
        writer = Thread.ofVirtual().name("setu-partition-writer").unstarted(this::writeLoop);
        writer.start();
    }

    public long append(byte[] value) throws IOException { return appendValues(List.of(MessageValue.copyOf(value))); }
    public long appendBatch(List<byte[]> values) throws IOException {
        return appendValues(values.stream().map(MessageValue::copyOf).toList());
    }
    public long appendValues(List<MessageValue> values) throws IOException {
        return await(enqueue(values), "Partition append failed");
    }

    public CompletableFuture<Long> appendValuesAsync(List<MessageValue> values) throws IOException {
        var result = new CompletableFuture<Long>();
        enqueue(values).whenComplete((offset, failure) ->
                Thread.ofVirtual().name("setu-write-completion").start(() -> {
                    // Completing a public future can run arbitrary caller callbacks.
                    // Keep both success and failure callbacks off the partition writer:
                    // they may synchronously append again or close and join that writer.
                    if (failure == null) result.complete(offset);
                    else result.completeExceptionally(failure);
                }));
        return result;
    }

    private CompletableFuture<Long> enqueue(List<MessageValue> values) throws IOException {
        if (values.isEmpty()) throw new IllegalArgumentException("empty batch");
        List<MessageValue> owned = List.copyOf(values);
        long bytes = 0;
        MessageValue previous = null;
        for (MessageValue value : owned) {
            bytes = Math.addExact(bytes, 32L + (value.sharesFrameWith(previous) ? 0 : value.retainedArrayLength()));
            previous = value;
        }
        PendingAppend pending = new PendingAppend(owned, bytes, new CompletableFuture<>());
        while (true) {
            GroupCommitState before = state.get();
            if (before.closing()) throw new IOException("Partition writer is closed");
            if (before.failed() != null) throw new IOException("Partition writer failed", before.failed());
            if (before.requests() >= maximumRequests || bytes > maximumBytes - before.bytes())
                throw new LogCapacityExceededException("Partition write queue is full; retry with backoff");
            GroupCommitState after = new GroupCommitState(new GroupCommitEntry(pending, before.head()), before.bytes() + bytes,
                    before.requests() + 1, false, null);
            if (state.compareAndSet(before, after)) break;
        }
        LockSupport.unpark(writer);
        // Interruption cannot cancel an accepted write or hide its durability result.
        return pending.result();
    }

    private void writeLoop() {
        while (true) {
            GroupCommitState ready = state.get();
            if (ready.head() == null) {
                if (ready.closing() || ready.failed() != null) return;
                LockSupport.park(this);
                if (Thread.interrupted()) { failAll(new IOException("Partition writer interrupted"), null); return; }
                continue;
            }
            long started = System.nanoTime();
            long remaining;
            while (!(ready = state.get()).closing() && ready.bytes() < targetGroupBytes
                    && ready.requests() < maximumRequests
                    && (remaining = lingerNanos - (System.nanoTime() - started)) > 0) {
                LockSupport.parkNanos(this, remaining);
                if (Thread.interrupted()) { failAll(new IOException("Partition writer interrupted"), null); return; }
            }
            GroupCommitEntry detached = detach();
            if (detached == null) continue;
            try {
                List<PendingAppend> group = fifo(detached);
                List<MessageValue> values;
                if (group.size() == 1) values = group.getFirst().values();
                else {
                    int count = 0;
                    for (PendingAppend pending : group) count = Math.addExact(count, pending.values().size());
                    values = new ArrayList<>(count);
                    for (PendingAppend pending : group) values.addAll(pending.values());
                }
                long flushStarted = System.nanoTime();
                long offset;
                try { offset = delegate.appendValues(values); } // forces every touched segment before acknowledgement
                finally { flushNanos.add(System.nanoTime() - flushStarted); flushCount.increment(); }
                release(group); // returned callers can immediately reuse the released capacity
                for (PendingAppend pending : group) {
                    pending.result().complete(offset);
                    offset += pending.values().size();
                }
            } catch (Throwable e) {
                failAll(e instanceof IOException io ? io : new IOException(e), detached);
                if (e instanceof Error error) throw error;
                return;
            }
        }
    }

    private GroupCommitEntry detach() {
        while (true) {
            GroupCommitState before = state.get();
            if (before.head() == null) return null;
            GroupCommitState after = new GroupCommitState(null, before.bytes(), before.requests(), before.closing(), before.failed());
            if (state.compareAndSet(before, after)) return before.head();
        }
    }

    private static List<PendingAppend> fifo(GroupCommitEntry head) {
        List<PendingAppend> group = new ArrayList<>();
        for (GroupCommitEntry entry = head; entry != null; entry = entry.next()) group.add(entry.pending());
        Collections.reverse(group);
        return group;
    }

    private void release(List<PendingAppend> group) {
        long bytes = 0;
        for (PendingAppend pending : group) bytes += pending.bytes();
        while (true) {
            GroupCommitState before = state.get();
            GroupCommitState after = new GroupCommitState(before.head(), before.bytes() - bytes,
                    before.requests() - group.size(), before.closing(), before.failed());
            if (state.compareAndSet(before, after)) return;
        }
    }

    private void failAll(IOException failure, GroupCommitEntry active) {
        while (true) {
            GroupCommitState before = state.get();
            if (state.compareAndSet(before, new GroupCommitState(null, 0, 0, before.closing(), failure))) {
                for (GroupCommitEntry entry = active; entry != null; entry = entry.next())
                    entry.pending().result().completeExceptionally(failure);
                for (GroupCommitEntry entry = before.head(); entry != null; entry = entry.next())
                    entry.pending().result().completeExceptionally(failure);
                return;
            }
        }
    }

    public List<LogRecord> read(long offset, int bytes) throws IOException { return delegate.read(offset, bytes); }
    public List<LogRecord> read(long offset, int bytes, ReadAdmission admission) throws IOException { return delegate.read(offset, bytes, admission); }
    public long nextOffset() { return delegate.nextOffset(); }
    public void awaitPendingWrites() throws IOException {
        while (true) {
            GroupCommitState current = state.get();
            if (current.failed() != null) throw new IOException("Partition writer failed", current.failed());
            if (current.requests() == 0) return;
            if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted while draining partition writes");
            LockSupport.parkNanos(this, 100_000);
        }
    }
    public long firstOffset() { return delegate.firstOffset(); }
    /** Includes the active durable flush as well as waiting requests. */
    public long outstandingBytes() { return state.get().bytes(); }
    public int outstandingRequests() { return state.get().requests(); }
    /** Full delegate append/force time, including failed flush attempts. */
    public long flushNanos() { return flushNanos.sum(); }
    public long flushCount() { return flushCount.sum(); }
    public void discardBefore(long offset) throws IOException { delegate.discardBefore(offset); }
    public void discardExpiredFiles(long retentionMillis) throws IOException { delegate.discardExpiredFiles(retentionMillis); }
    public void close() throws IOException {
        while (true) {
            GroupCommitState before = state.get();
            if (before.closing() || state.compareAndSet(before,
                    new GroupCommitState(before.head(), before.bytes(), before.requests(), true, before.failed()))) break;
        }
        LockSupport.unpark(writer);
        boolean interrupted = false;
        try {
            while (writer.isAlive()) {
                try { writer.join(); }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (closeDelegate.compareAndSet(false, true)) {
                try { delegate.close(); closed.complete(null); }
                catch (Throwable e) { closed.completeExceptionally(e); if (e instanceof Error error) throw error; }
            }
            await(closed, "Partition close failed");
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static <T> T await(CompletableFuture<T> future, String message) throws IOException {
        boolean interrupted = false;
        try {
            while (true) {
                try { return future.get(); }
                catch (InterruptedException e) { interrupted = true; }
                catch (ExecutionException e) { throw new IOException(message, e.getCause()); }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
