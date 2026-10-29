package io.setu.storage.log;

import java.util.ArrayDeque;

final class MemoryLogCursor {
    final ArrayDeque<MemoryLogNode> path = new ArrayDeque<>();
    MemoryLogCursor(MemoryLogNode node, long start) {
        while (node != null) {
            if (node.chunk.base() + node.chunk.count() <= start) node = node.right;
            else { path.push(node); node = node.left; }
        }
    }
    MemoryLogChunk next() {
        if (path.isEmpty()) return null;
        MemoryLogNode next = path.pop();
        MemoryLogNode node = next.right;
        while (node != null) { path.push(node); node = node.left; }
        return next.chunk;
    }
}
