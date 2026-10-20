package io.setu.server.connection;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Absolute I/O deadline; cancellation serializes with expiry before the next exchange starts. */
final class ChannelDeadline implements AutoCloseable {
    private static final ScheduledThreadPoolExecutor TIMER = timer();
    private final ScheduledFuture<?> future;
    private final long started = System.nanoTime();
    private final long timeoutNanos;
    private boolean cancelled;

    ChannelDeadline(SocketChannel channel, int millis) {
        timeoutNanos = TimeUnit.MILLISECONDS.toNanos(millis);
        future = TIMER.schedule(() -> expire(channel), millis, TimeUnit.MILLISECONDS);
    }

    synchronized void complete() throws IOException {
        if (System.nanoTime() - started >= timeoutNanos)
            throw new java.net.SocketTimeoutException("Connection I/O deadline exceeded");
        close();
    }

    private synchronized void expire(SocketChannel channel) {
        if (cancelled) return;
        try { channel.close(); } catch (IOException ignored) { }
    }

    @Override
    public synchronized void close() {
        cancelled = true;
        future.cancel(false);
    }

    private static ScheduledThreadPoolExecutor timer() {
        var timer = new ScheduledThreadPoolExecutor(1, task ->
                Thread.ofPlatform().daemon().name("setu-io-deadlines").unstarted(task));
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }
}
