package io.setu.storage.uring;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

final class UringOperation {
    int opcode;
    final UringFile file;
    ByteBuffer payload;
    long offset;
    boolean metadata;
    byte[] path;
    CompletableFuture<Integer> completion;
    UringOperation(int opcode, UringFile file, ByteBuffer payload, long offset, boolean metadata, byte[] path) {
        this.file = file;
        reset(opcode, payload, offset, metadata, path);
    }
    UringOperation reset(int opcode, ByteBuffer payload, long offset, boolean metadata, byte[] path) {
        this.opcode = opcode; this.payload = payload;
        this.offset = offset; this.metadata = metadata; this.path = path;
        completion = new CompletableFuture<>();
        return this;
    }
    int bytes() { return payload == null ? 0 : payload.remaining(); }
}
