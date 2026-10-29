package io.setu.storage.log;

import java.io.DataInput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/** A privately read frame; retaining a slice retains the frame's entire backing array. */
public final class MessageFrame {
    private final byte[] bytes;
    MessageFrame(byte[] bytes) { this.bytes = bytes; }

    public static MessageFrame readFrom(DataInput input, int length) throws IOException {
        if (length < 0) throw new IllegalArgumentException("negative frame length");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new MessageFrame(bytes);
    }

    public ByteBuffer cursor() { return ByteBuffer.wrap(bytes).asReadOnlyBuffer(); }
    /** Defensive compatibility copy for handlers that accept mutable byte arrays. */
    public byte[] copy() { return bytes.clone(); }
    public int length() { return bytes.length; }
    public String asciiAt(int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        return new String(bytes, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }
    public MessageValue valueAt(int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        return length == 0 ? MessageValue.empty() : new MessageValue(bytes, offset, length, this);
    }
}
