package io.setu.storage.log;

import io.setu.storage.uring.FileForceEvent;
import io.setu.storage.uring.UringFileIo;
import net.openhft.chronicle.bytes.Bytes;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Automatic FILE backend selection; no runtime backend or ring-tuning properties. */
final class FileSyncs {
    private static final Pool POOL = new Pool(UringFileIo.isSupportedPlatform(), UringFileIo::createWithBackpressure);

    private FileSyncs() { }

    static ByteBuffer allocatePayload(int size) {
        return UringFileIo.isSupportedPlatform() ? UringFileIo.allocateBuffer(size) : ByteBuffer.allocate(size);
    }

    static ChronicleFileSync open(Path path) throws IOException {
        UringFileIo io = POOL.acquire();
        if (io == null) return new ChronicleFileSync(path);
        try { return new UringSync(path, io); }
        catch (IOException | RuntimeException | Error failure) {
            try { POOL.release(); } catch (IOException cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @FunctionalInterface interface Factory { UringFileIo create() throws IOException; }

    /** One bounded owner per process while files are open; the last close joins it. */
    static final class Pool {
        private final Factory factory;
        private boolean unavailable;
        private UringFileIo shared;
        private int leases;

        Pool(boolean supported, Factory factory) { this.factory = factory; unavailable = !supported; }

        synchronized UringFileIo acquire() throws IOException {
            if (unavailable) return null;
            if (shared == null) {
                try { shared = factory.create(); }
                catch (UringFileIo.UnavailableException failure) {
                    unavailable = true;
                    LoggerFactory.getLogger(FileSyncs.class).warn(
                            "io_uring unavailable; using FileChannel for FILE storage: {}", failure.getMessage());
                    return null;
                }
            }
            leases++;
            return shared;
        }

        synchronized void release() throws IOException {
            if (leases <= 0) throw new IllegalStateException("No io_uring lease to release");
            if (--leases == 0) {
                UringFileIo closing = shared;
                shared = null;
                closing.close();
            }
        }
    }

    private static final class UringSync extends ChronicleFileSync {
        private final UringFileIo.File file;
        private final String eventPath;
        private long forcedSize = -1;
        private boolean closed;

        UringSync(Path path, UringFileIo io) throws IOException {
            super(path);
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
            try { POOL.release(); } catch (IOException problem) { failure = combine(failure, problem); }
            if (failure != null) throw failure;
        }

        private static IOException combine(IOException original, IOException next) {
            if (original == null) return next;
            if (original != next) original.addSuppressed(next);
            return original;
        }
    }
}
