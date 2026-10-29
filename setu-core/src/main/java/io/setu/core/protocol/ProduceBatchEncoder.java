package io.setu.core.protocol;

import java.io.IOException;
import java.util.List;

/**
 * Scratch encoder for serialized client exchanges. The returned frame is valid only until
 * the next encode; callers must finish writing it before reusing this encoder.
 */
public final class ProduceBatchEncoder {
    private static final int MAX_RETAINED_BYTES = 1024 * 1024;
    private byte[] buffer;

    public byte[] encode(int correlation, String topic, int partition, List<byte[]> values) throws IOException {
        return encode(correlation, topic, partition, values, TraceContext.NONE);
    }

    public byte[] encode(int correlation, String topic, int partition, List<byte[]> values,
                         TraceContext traceContext) throws IOException {
        return ProtocolCodec.encodeProduceBatch(correlation, topic, partition, values, this, traceContext);
    }

    byte[] allocate(int size) {
        if (size > MAX_RETAINED_BYTES) return new byte[size];
        // Exact length keeps framing honest when batch size or topic length changes.
        if (buffer == null || buffer.length != size) buffer = new byte[size];
        return buffer;
    }

    public void clear() { buffer = null; }
}
