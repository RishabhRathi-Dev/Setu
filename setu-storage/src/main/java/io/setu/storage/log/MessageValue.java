package io.setu.storage.log;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Immutable payload shared by storage snapshots and protocol records. */
public final class MessageValue {
    private static final MessageValue EMPTY = new MessageValue(new byte[0]);
    private final byte[] bytes;

    private MessageValue(byte[] ownedBytes) { bytes = ownedBytes; }

    public static MessageValue copyOf(byte[] value) {
        return new MessageValue(Objects.requireNonNull(value, "value").clone());
    }

    public static MessageValue readFrom(ByteBuffer input, int length) {
        if (length < 0 || length > input.remaining())
            throw new IllegalArgumentException("length must fit in the input buffer");
        if (length == 0) return EMPTY;
        byte[] bytes = new byte[length];
        input.get(bytes);
        return new MessageValue(bytes);
    }

    public int length() { return bytes.length; }

    public static MessageValue empty() { return EMPTY; }

    /** Creates a private payload array directly from the decoder. */
    public static MessageValue readFrom(java.io.DataInput input, int length) throws java.io.IOException {
        if (length < 0) throw new IllegalArgumentException("negative length");
        if (length == 0) return EMPTY;
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new MessageValue(bytes);
    }

    public void writeTo(java.io.DataOutput output) throws java.io.IOException { output.write(bytes); }

    public byte[] copy() { return bytes.clone(); }

    public void copyTo(ByteBuffer output) { output.put(bytes); }
}
