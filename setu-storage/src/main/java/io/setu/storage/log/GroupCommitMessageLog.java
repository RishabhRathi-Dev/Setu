package io.setu.storage.log;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** A bounded partition writer. Adjacent requests share a durable append before any future completes. */
public final class GroupCommitMessageLog implements MessageLog {
    private final MessageLog delegate;
    private final long maximumBytes;
    private final int maximumRequests;
    private final long lingerNanos;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    // Includes the group being flushed, not just waiting requests.
    private long outstandingBytes;
    private int outstandingRequests;
    private boolean closing;
    private IOException failed;
    private Thread writer;

    public GroupCommitMessageLog(MessageLog delegate, long maximumBytes, int maximumRequests, long lingerNanos) {
        if (maximumBytes <= 0 || maximumRequests <= 0 || lingerNanos < 0)
            throw new IllegalArgumentException("invalid group commit limits");
        this.delegate = java.util.Objects.requireNonNull(delegate);
        this.maximumBytes = maximumBytes;
        this.maximumRequests = maximumRequests;
        this.lingerNanos = lingerNanos;
    }

    public long append(byte[] value) throws IOException { return appendValues(List.of(MessageValue.copyOf(value))); }
    public long appendBatch(List<byte[]> values) throws IOException {
        return appendValues(values.stream().map(MessageValue::copyOf).toList());
    }
    public long appendValues(List<MessageValue> values) throws IOException {
        if (values.isEmpty()) throw new IllegalArgumentException("empty batch");
        List<MessageValue> owned = List.copyOf(values);
        long bytes = 0;
        for (MessageValue value : owned) bytes = Math.addExact(bytes, value.length() + 32L);
        Pending pending = new Pending(owned, bytes, new CompletableFuture<>());
        lock.lock();
        try {
            if (closing) throw new IOException("Partition writer is closed");
            if (failed != null) throw new IOException("Partition writer failed", failed);
            if (outstandingRequests >= maximumRequests || bytes > maximumBytes - outstandingBytes)
                throw new LogCapacityExceededException("Partition write queue is full; retry with backoff");
            outstandingBytes += bytes;
            outstandingRequests++;
            queue.addLast(pending);
            if (writer == null) writer = Thread.ofVirtual().name("setu-partition-writer").start(this::writeLoop);
            available.signal();
        } finally { lock.unlock(); }
        // An interrupted caller still waits for the durability result: interruption cannot cancel an accepted write.
        boolean interrupted = false;
        try {
            while (true) {
                try { return pending.result.get(); }
                catch (InterruptedException e) { interrupted = true; }
                catch (ExecutionException e) { throw new IOException("Partition append failed", e.getCause()); }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private void writeLoop() {
        while (true) {
            List<Pending> group = new ArrayList<>();
            lock.lock();
            try {
                while (queue.isEmpty() && !closing) available.await();
                if (queue.isEmpty()) return;
                long remaining = lingerNanos;
                while (!closing && remaining > 0 && outstandingBytes < maximumBytes / 2)
                    remaining = available.awaitNanos(remaining);
                // Flush a bounded group, including every currently queued request.
                while (!queue.isEmpty()) group.add(queue.removeFirst());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failAll(new IOException("Partition writer interrupted", e), group);
                return;
            } finally { lock.unlock(); }
            try {
                List<MessageValue> values = new ArrayList<>();
                for (Pending pending : group) values.addAll(pending.values);
                long offset = delegate.appendValues(values); // includes force(true) on all touched segments
                for (Pending pending : group) {
                    pending.result.complete(offset);
                    offset += pending.values.size();
                }
                release(group);
            } catch (Throwable e) {
                failAll(e instanceof IOException io ? io : new IOException(e), group);
                if (e instanceof Error error) throw error;
                return;
            }
        }
    }

    private void release(List<Pending> group) {
        lock.lock();
        try {
            for (Pending pending : group) { outstandingBytes -= pending.bytes; outstandingRequests--; }
        } finally { lock.unlock(); }
    }

    private void failAll(IOException failure, List<Pending> group) {
        lock.lock();
        try {
            failed = failure;
            group.addAll(queue);
            queue.clear();
            for (Pending pending : group) pending.result.completeExceptionally(failure);
            outstandingBytes = 0;
            outstandingRequests = 0;
        } finally { lock.unlock(); }
    }

    public List<LogRecord> read(long offset, int bytes) throws IOException { return delegate.read(offset, bytes); }
    public List<LogRecord> read(long offset, int bytes, ReadAdmission admission) throws IOException { return delegate.read(offset, bytes, admission); }
    public long nextOffset() { return delegate.nextOffset(); }
    public long firstOffset() { return delegate.firstOffset(); }
    public void discardBefore(long offset) throws IOException { delegate.discardBefore(offset); }
    public void close() throws IOException {
        Thread current;
        lock.lock();
        try { closing = true; available.signalAll(); current = writer; }
        finally { lock.unlock(); }
        boolean interrupted = false;
        if (current != null) {
            while (current.isAlive()) {
                try { current.join(); }
                catch (InterruptedException e) { interrupted = true; }
            }
        }
        try { delegate.close(); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private record Pending(List<MessageValue> values, long bytes, CompletableFuture<Long> result) { }
}
