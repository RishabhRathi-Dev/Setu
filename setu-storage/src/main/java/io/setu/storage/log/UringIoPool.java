package io.setu.storage.log;

import io.setu.storage.uring.UringUnavailableException;
import io.setu.storage.uring.UringFileIo;
import org.slf4j.LoggerFactory;
import java.io.IOException;

/** One bounded owner per process while files are open; the last close joins it. */
final class UringIoPool {
    private final UringIoFactory factory;
    private boolean unavailable;
    private UringFileIo shared;
    private int leases;

    UringIoPool(boolean supported, UringIoFactory factory) { this.factory = factory; unavailable = !supported; }

    synchronized UringFileIo acquire() throws IOException {
        if (unavailable) return null;
        if (shared == null) {
            try { shared = factory.create(); }
            catch (UringUnavailableException failure) {
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
