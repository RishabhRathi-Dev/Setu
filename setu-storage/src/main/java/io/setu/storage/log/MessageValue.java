package io.setu.storage.log;

import net.openhft.chronicle.bytes.Bytes;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/** Immutable payload shared by storage snapshots and protocol records. */
public final class MessageValue {
    private static final MessageValue EMPTY = new MessageValue(new byte[0]);
    private final byte[] bytes;
    private final int offset;
    private final int length;
    private final MessageFrame frame;

    private MessageValue(byte[] ownedBytes) { this(ownedBytes, 0, ownedBytes.length); }
    private MessageValue(byte[] bytes, int offset, int length) { this(bytes, offset, length, null); }
    MessageValue(byte[] bytes, int offset, int length, MessageFrame frame) {
        this.bytes = bytes;
        this.offset = offset;
        this.length = length;
        this.frame = frame;
    }

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

    public int length() { return length; }

    /** Conservative retained allocation charge when a slice outlives its siblings. */
    public int retainedArrayLength() { return bytes.length; }

    /** Does not expose the mutable backing array. Used for conservative run-based accounting. */
    boolean sharesFrameWith(MessageValue previous) {
        return frame != null && previous != null && frame == previous.frame;
    }

    long retainedBackingBytes() {
        return ((bytes.length + 24L + 7) & ~7L) + (frame == null ? 0 : 24);
    }

    long retainedFrameBytes() { return frame == null ? 0 : retainedBackingBytes(); }

    public static MessageValue empty() { return EMPTY; }

    /** Creates a private payload array directly from the decoder. */
    public static MessageValue readFrom(DataInput input, int length) throws IOException {
        if (length < 0) throw new IllegalArgumentException("negative length");
        if (length == 0) return EMPTY;
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new MessageValue(bytes);
    }

    public void writeTo(DataOutput output) throws IOException { output.write(bytes, offset, length); }

    /** Internal bulk copy without stream adapters or exposing the backing array. */
    void writeTo(Bytes<?> output) { output.write(bytes, offset, length); }

    public byte[] copy() { return Arrays.copyOfRange(bytes, offset, offset + length); }

    public void copyTo(ByteBuffer output) { output.put(bytes, offset, length); }

    void updateChecksum(java.util.zip.CRC32C checksum) { checksum.update(bytes, offset, length); }
}
