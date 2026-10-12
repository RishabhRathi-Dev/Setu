package io.setu.storage.log;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Objects;

/** Immutable payload shared by storage snapshots and protocol records. */
public final class MessageValue {
    private static final MessageValue EMPTY = new MessageValue(new byte[0]);
    private final byte[] bytes;
    private final int offset;
    private final int length;

    private MessageValue(byte[] ownedBytes) { this(ownedBytes, 0, ownedBytes.length); }
    private MessageValue(byte[] bytes, int offset, int length) {
        this.bytes = bytes;
        this.offset = offset;
        this.length = length;
    }

    /** A privately read frame; retaining a slice retains the frame's entire backing array. */
    public static final class Frame {
        private final byte[] bytes;
        private Frame(byte[] bytes) { this.bytes = bytes; }

        public static Frame readFrom(DataInput input, int length) throws IOException {
            if (length < 0) throw new IllegalArgumentException("negative frame length");
            byte[] bytes = new byte[length];
            input.readFully(bytes);
            return new Frame(bytes);
        }

        static Frame readFrom(FileChannel channel, int length) throws IOException {
            byte[] bytes = new byte[length];
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw new EOFException("Unexpected end of log window");
            }
            return new Frame(bytes);
        }
        public ByteBuffer cursor() { return ByteBuffer.wrap(bytes).asReadOnlyBuffer(); }
        public int length() { return bytes.length; }
        public MessageValue valueAt(int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            return length == 0 ? EMPTY : new MessageValue(bytes, offset, length);
        }
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

    public byte[] copy() { return Arrays.copyOfRange(bytes, offset, offset + length); }

    public void copyTo(ByteBuffer output) { output.put(bytes, offset, length); }
}
