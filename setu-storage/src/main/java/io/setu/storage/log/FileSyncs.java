package io.setu.storage.log;

import io.setu.storage.uring.UringFileIo;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Automatic FILE backend selection; no runtime backend or ring-tuning properties. */
final class FileSyncs {
    private static final UringIoPool POOL = new UringIoPool(UringFileIo.isSupportedPlatform(), UringFileIo::createWithBackpressure);

    private FileSyncs() { }

    static ByteBuffer allocatePayload(int size) {
        return UringFileIo.isSupportedPlatform() ? UringFileIo.allocateBuffer(size) : ByteBuffer.allocate(size);
    }

    static ChronicleFileSync open(Path path) throws IOException {
        UringFileIo io = POOL.acquire();
        if (io == null) return new ChronicleFileSync(path);
        try { return new UringFileSync(path, io, POOL); }
        catch (IOException | RuntimeException | Error failure) {
            try { POOL.release(); } catch (IOException cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
}
