package io.setu.storage.log;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.io.IOException;

/** One admitted append; synchronous callers need only a direct writer-to-caller signal. */
final class PendingAppend {
    private final List<MessageValue> values;
    private final long bytes;
    private final CompletableFuture<Long> result;
    private final Thread waiter;
    private volatile boolean completed;
    private long offset;
    private IOException failure;

    PendingAppend(List<MessageValue> values, long bytes, boolean asynchronous) {
        this.values = values;
        this.bytes = bytes;
        result = asynchronous ? new CompletableFuture<>() : null;
        waiter = asynchronous ? null : Thread.currentThread();
    }

    List<MessageValue> values() { return values; }
    long bytes() { return bytes; }
    CompletableFuture<Long> result() { return result; }

    long await() throws IOException {
        boolean interrupted = false;
        try {
            while (!completed) {
                LockSupport.park(this);
                if (Thread.interrupted()) interrupted = true;
            }
            if (failure != null) throw new IOException("Partition append failed", failure);
            return offset;
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    void complete(long offset, IOException failure) {
        if (completed) return;
        this.offset = offset;
        this.failure = failure;
        completed = true;
        if (result == null) {
            LockSupport.unpark(waiter);
        } else {
            // Public callbacks can append or close. Keep them off the partition writer
            // and avoid retaining this request's payload in a blocked callback.
            CompletableFuture<Long> future = result;
            Thread.ofVirtual().name("setu-write-completion").start(() -> {
                if (failure == null) future.complete(offset);
                else future.completeExceptionally(failure);
            });
        }
    }
}
