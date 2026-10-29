package io.setu.storage.log;

import io.setu.storage.uring.UringFile;
import io.setu.storage.uring.UringFileIo;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/** Uses the Setu-owned implementation without third-party Java/native io_uring libraries. */
final class SetuUringBackend implements ComparisonSync.Backend {
    private static UringFileIo shared;
    private static int owners;
    private final UringFile file;
    private final FileChannel metadataChannel;
    private boolean closed;
    private static synchronized UringFileIo acquire() throws IOException {
        if (shared == null) shared = UringFileIo.create();
        owners++;
        return shared;
    }
    private static synchronized void release() throws IOException {
        if (--owners == 0) {
            UringFileIo closing = shared;
            shared = null;
            closing.close();
        }
    }
    SetuUringBackend(Path path, FileChannel metadataChannel) throws IOException {
        this.metadataChannel = metadataChannel;
        UringFileIo executor = acquire();
        try { file = executor.open(path); }
        catch (IOException failure) {
            try { release(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    @Override public void write(MemorySegment data, long position) throws IOException {
        file.writeFully(data.asByteBuffer(), position);
    }
    @Override public void force(boolean metadata) throws IOException {
        // Match the jasyncfio adapter's first-use/growth metadata barrier.
        if (metadata) metadataChannel.force(true);
        else file.force(false);
    }
    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        try { file.close(); } catch (IOException problem) { failure = problem; }
        try { release(); } catch (IOException cleanup) {
            if (failure == null) failure = cleanup;
            else failure.addSuppressed(cleanup);
        }
        if (failure != null) throw failure;
    }
}
