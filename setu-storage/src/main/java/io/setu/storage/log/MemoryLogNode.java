package io.setu.storage.log;

import static io.setu.storage.log.InMemoryMessageLog.height;
import static io.setu.storage.log.InMemoryMessageLog.bytes;

final class MemoryLogNode {
    final MemoryLogChunk chunk;
    final MemoryLogNode left, right;
    final int height;
    final long bytes;
    MemoryLogNode(MemoryLogChunk chunk, MemoryLogNode left, MemoryLogNode right) {
        this.chunk = chunk; this.left = left; this.right = right;
        height = 1 + Math.max(height(left), height(right));
        bytes = chunk.bytes() + bytes(left) + bytes(right);
    }
}
