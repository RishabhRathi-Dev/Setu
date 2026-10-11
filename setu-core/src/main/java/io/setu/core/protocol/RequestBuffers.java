package io.setu.core.protocol;

import java.io.IOException;

/** Adds decoded object credit to the transport's existing body reservation before allocating batch items. */
public final class RequestBuffers {
    private static final ThreadLocal<BufferBudget.Lease> CURRENT = new ThreadLocal<>();
    private RequestBuffers() { }
    public static AutoCloseable activate(BufferBudget.Lease lease) {
        BufferBudget.Lease previous = CURRENT.get();
        CURRENT.set(lease);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public static void reserveObjects(long bytes) throws IOException {
        BufferBudget.Lease lease = CURRENT.get();
        if (lease != null) lease.expand(bytes);
    }
}
