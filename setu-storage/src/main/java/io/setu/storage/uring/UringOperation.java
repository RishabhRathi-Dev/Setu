package io.setu.storage.uring;

import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.concurrent.locks.LockSupport;

final class UringOperation {
    int opcode;
    final UringFile file;
    ByteBuffer payload;
    long offset;
    boolean metadata;
    byte[] path;
    Thread waiter;
    private volatile boolean completed;
    private int result;
    private IOException failure;
    UringOperation(int opcode, UringFile file, ByteBuffer payload, long offset, boolean metadata, byte[] path) {
        this.file = file;
        reset(opcode, payload, offset, metadata, path);
    }
    UringOperation reset(int opcode, ByteBuffer payload, long offset, boolean metadata, byte[] path) {
        this.opcode = opcode; this.payload = payload;
        this.offset = offset; this.metadata = metadata; this.path = path;
        waiter = Thread.currentThread();
        failure = null;
        completed = false;
        return this;
    }
    int bytes() { return payload == null ? 0 : payload.remaining(); }

    int await() throws IOException {
        boolean interrupted = false;
        try {
            while (!completed) {
                LockSupport.park(this);
                if (Thread.interrupted()) interrupted = true;
            }
            if (failure != null) throw failure;
            return result;
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    void complete(int result, IOException failure) {
        this.result = result;
        this.failure = failure;
        // Capture before publication: the caller can immediately reuse this command.
        Thread caller = waiter;
        completed = true;
        LockSupport.unpark(caller);
    }
}
