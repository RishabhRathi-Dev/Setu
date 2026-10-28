package io.setu.storage.log;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import one.jasyncfio.AsyncFile;
import one.jasyncfio.EventExecutor;
import one.jasyncfio.OpenOption;

/** Published Java io_uring library; one shared sleeping event loop, without busy polling. */
final class JavaUringBackend implements ComparisonSync.Backend {
    private static EventExecutor shared;
    private static int owners;
    private final AsyncFile file;
    private final FileChannel metadataChannel;
    private boolean closed;

    private static synchronized EventExecutor acquire() {
        if (shared == null) shared = EventExecutor.builder().entries(16).sleepTimeout(0).build();
        owners++;
        return shared;
    }
    private static synchronized void release() {
        if (--owners == 0) { shared.close(); shared = null; }
    }
    JavaUringBackend(Path path, FileChannel metadataChannel) throws IOException {
        EventExecutor executor = acquire();
        this.metadataChannel = metadataChannel;
        try { file = await(AsyncFile.open(path, executor, OpenOption.READ_WRITE, OpenOption.CLOSE_ON_EXIT)); }
        catch (Throwable failure) { release(); throw ComparisonSync.io(failure); }
    }
    private static <T> T await(CompletableFuture<T> operation) throws IOException {
        try { return operation.get(30, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
        catch (Exception failure) { throw new IOException(failure); }
    }
    @Override public void write(MemorySegment data, long position) throws IOException {
        ByteBuffer buffer = data.asByteBuffer();
        while (buffer.hasRemaining()) {
            int remaining = buffer.remaining();
            int written = await(file.write(buffer, position, remaining));
            if (written <= 0 || written > remaining) throw new IOException("Invalid write completion: " + written);
            position += written;
        }
    }
    @Override public void force(boolean metadata) throws IOException {
        // The public library exposes fdatasync. Preserve Setu's full metadata barrier on first force/growth.
        if (metadata) metadataChannel.force(true);
        else ComparisonSync.check(await(file.dataSync()), "fdatasync");
    }
    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        try { ComparisonSync.check(await(file.close()), "close"); }
        finally { release(); }
    }
}
