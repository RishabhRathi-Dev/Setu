package io.setu.storage.log;

import static io.setu.storage.log.InMemoryMessageLog.recordMemoryBytes;
import java.util.Arrays;

record MemoryLogChunk(long base, MessageValue[] records, long[] appendedNanos, int count,
                     int discarded, long payloadBytes, long overheadBytes) {
    long bytes() { return payloadBytes + overheadBytes; }
    MemoryLogChunk discard(long target) {
        int removed = (int) (target - base);
        MessageValue[] retained = records.clone();
        long bytes = 0;
        MessageValue previous = null;
        for (int i = removed; i < count; i++) {
            bytes += recordMemoryBytes(retained[i], previous);
            previous = retained[i];
        }
        Arrays.fill(retained, discarded, removed, null);
        // Timestamps contain no payload references and can remain shared.
        return new MemoryLogChunk(base, retained, appendedNanos, count, removed, bytes, overheadBytes);
    }
}
