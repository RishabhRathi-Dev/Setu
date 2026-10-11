package io.setu.storage.log;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Immutable payload shared by storage snapshots and protocol records. */
public final class MessageValue {
    private final byte[] bytes;

    private MessageValue(byte[] ownedBytes) { bytes = ownedBytes; }

    public static MessageValue copyOf(byte[] value) {
        return new MessageValue(Objects.requireNonNull(value, "value").clone());
    }

    public static MessageValue readFrom(ByteBuffer input, int length) {
        if (length < 0 || length > input.remaining())
            throw new IllegalArgumentException("length must fit in the input buffer");
        byte[] bytes = new byte[length];
        input.get(bytes);
        return new MessageValue(bytes);
    }

    public int length() { return bytes.length; }

    public byte[] copy() { return bytes.clone(); }

    public void copyTo(ByteBuffer output) { output.put(bytes); }
}
