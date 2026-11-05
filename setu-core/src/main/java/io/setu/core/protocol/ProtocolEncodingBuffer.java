package io.setu.core.protocol;

import java.io.ByteArrayOutputStream;

final class ProtocolEncodingBuffer extends ByteArrayOutputStream {
    ProtocolEncodingBuffer(int capacity) { super(capacity); }

    // Exact-sized encodings transfer their buffer; any overestimate returns a trimmed copy.
    // The stream is local to the encoder and is never used after this call.
    byte[] finish() { return count == buf.length ? buf : toByteArray(); }
}
