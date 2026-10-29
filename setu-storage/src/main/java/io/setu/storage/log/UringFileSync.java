package io.setu.storage.log;

import io.setu.storage.uring.UringFile;
import io.setu.storage.uring.FileForceEvent;
import io.setu.storage.uring.UringFileIo;
import net.openhft.chronicle.bytes.Bytes;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

final class UringFileSync extends ChronicleFileSync {
    private final UringFile file;
    private final UringIoPool pool;
    private final String eventPath;
    private long forcedSize = -1;
    private boolean closed;

    UringFileSync(Path path, UringFileIo io, UringIoPool pool) throws IOException {
        super(path);
        this.pool = pool;
        eventPath = path.toString();
        try { file = io.open(path); }
        catch (IOException | RuntimeException | Error failure) {
            try { super.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override void write(Bytes<?> destination, ByteBuffer payload) throws IOException {
        int size = payload.remaining();
        file.writeFully(payload, destination.writePosition());
        destination.writeSkip(size);
    }

    @Override void force() throws IOException {
        long size = channel.size();
        if (size != forcedSize) {
            // Preserve the measured full metadata barrier on first use/growth.
            channel.force(true);
        } else {
            var event = new FileForceEvent();
            event.path = eventPath;
            event.metaData = false;
            event.begin();
            try { file.force(false); event.success = true; }
            finally { event.end(); event.commit(); }
        }
        forcedSize = size;
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        try { file.close(); } catch (IOException problem) { failure = problem; }
        try { super.close(); } catch (IOException problem) { failure = combine(failure, problem); }
        try { pool.release(); } catch (IOException problem) { failure = combine(failure, problem); }
        if (failure != null) throw failure;
    }

    private static IOException combine(IOException original, IOException next) {
        if (original == null) return next;
        if (original != next) original.addSuppressed(next);
        return original;
    }
}
