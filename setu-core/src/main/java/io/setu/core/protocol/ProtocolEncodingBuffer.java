package io.setu.core.protocol;

import java.io.ByteArrayOutputStream;

final class ProtocolEncodingBuffer extends ByteArrayOutputStream {
    ProtocolEncodingBuffer(int capacity) { super(capacity); }

    // Exact-sized batch encodings transfer their buffer; other commands return a trimmed copy.
    // The stream is local to the encoder and is never used after this call.
    byte[] finish() { return count == buf.length ? buf : toByteArray(); }
}
