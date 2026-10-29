package io.setu.core.protocol;

import java.io.IOException;

/** Adds decoded object credit to the transport's existing body reservation before allocating batch items. */
public final class RequestBuffers {
    private static final ThreadLocal<BufferLease> CURRENT = new ThreadLocal<>();
    private RequestBuffers() { }
    public static AutoCloseable activate(BufferLease lease) {
        BufferLease previous = CURRENT.get();
        CURRENT.set(lease);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public static void reserveObjects(long bytes) throws IOException {
        BufferLease lease = CURRENT.get();
        if (lease != null) lease.expand(bytes);
    }
}
