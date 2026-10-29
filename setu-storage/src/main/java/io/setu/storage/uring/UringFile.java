package io.setu.storage.uring;

import static io.setu.storage.uring.UringFileIo.*;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/** File handles belong to their executor; closing either drains admitted operations. */
public final class UringFile implements AutoCloseable {
    private final UringFileIo owner;
    // Per-file serialization makes a command reusable only after its CQE is delivered.
    final UringOperation operation = new UringOperation(0, this, null, 0, false, null);
    int fd = -1;
    volatile boolean closed;
    private IOException failed;
    UringFile(UringFileIo owner) { this.owner = owner; }
    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("io_uring file is closed");
        if (failed != null) throw new IOException("io_uring file failed; close and reopen it", failed);
    }
    /**
     * Advances source.position() after completed writes. The caller must not modify
     * the source until this method returns. Buffers from allocateBuffer are
     * retained through completion; other heap/native buffers are copied.
     * Accepted work completes despite interruption, then restores interrupt status.
     */
    public synchronized void writeFully(ByteBuffer source, long position) throws IOException {
        ensureOpen();
        if (position < 0 || position > Long.MAX_VALUE - source.remaining()) throw new IllegalArgumentException("Invalid write range");
        try {
            while (source.hasRemaining()) {
                int count = Math.min(source.remaining(), owner.options().maxWriteBytes());
                ByteBuffer part = source.slice(source.position(), count);
                // A caller can close an arena after an exceptional return. Copy
                // scoped memory here, also permitting caller-confined arenas.
                if (part.isDirect() && !ownsBuffer(MemorySegment.ofBuffer(part))) {
                    byte[] copy = new byte[count];
                    part.get(copy);
                    part = ByteBuffer.wrap(copy);
                }
                int written = owner.perform(operation.reset(WRITE, part, position, false, null), false);
                if (written <= 0 || written > count) throw new IOException("Invalid write completion: " + written);
                source.position(source.position() + written);
                position += written;
            }
        } catch (IOException failure) { failed = failure; throw failure; }
    }
    /** Full fsync when metadata=true, otherwise fdatasync. Both wait for the CQE. */
    public synchronized void force(boolean metadata) throws IOException {
        ensureOpen();
        try {
            if (owner.perform(operation.reset(FSYNC, null, 0, metadata, null), false) != 0)
                throw new IOException("Invalid fsync completion");
        } catch (IOException failure) { failed = failure; throw failure; }
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        owner.perform(operation.reset(CLOSE, null, 0, false, null), true);
    }
}
