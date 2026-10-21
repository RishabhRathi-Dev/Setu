package io.setu.storage.log;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.Objects;

/** Immutable payload shared by storage snapshots and protocol records. */
public final class MessageValue {
    private static final MessageValue EMPTY = new MessageValue(new byte[0]);
    private final byte[] bytes;
    private final int offset;
    private final int length;
    private final Frame frame;

    private MessageValue(byte[] ownedBytes) { this(ownedBytes, 0, ownedBytes.length); }
    private MessageValue(byte[] bytes, int offset, int length) { this(bytes, offset, length, null); }
    private MessageValue(byte[] bytes, int offset, int length, Frame frame) {
        this.bytes = bytes;
        this.offset = offset;
        this.length = length;
        this.frame = frame;
    }

    /** A privately read frame; retaining a slice retains the frame's entire backing array. */
    public static final class Frame {
        private final byte[] bytes;
        private Frame(byte[] bytes) { this.bytes = bytes; }

        /** Incremental channel reader; completion permanently removes writable access. */
        public static final class Reader {
            private final byte[] bytes;
            private ByteBuffer buffer;
            public Reader(int length) {
                if (length < 0) throw new IllegalArgumentException("negative frame length");
                bytes = new byte[length];
                buffer = ByteBuffer.wrap(bytes);
            }
            public int readFrom(ReadableByteChannel channel) throws IOException {
                if (buffer == null) throw new IllegalStateException("Frame already completed");
                return channel.read(buffer);
            }
            public boolean isComplete() { return buffer != null && !buffer.hasRemaining(); }
            public Frame finish() {
                if (!isComplete()) throw new IllegalStateException("Frame is incomplete or already completed");
                buffer = null;
                return new Frame(bytes);
            }
        }

        public static Frame readFrom(DataInput input, int length) throws IOException {
            if (length < 0) throw new IllegalArgumentException("negative frame length");
            byte[] bytes = new byte[length];
            input.readFully(bytes);
            return new Frame(bytes);
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
            return length == 0 ? EMPTY : new MessageValue(bytes, offset, length, this);
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

    public byte[] copy() { return Arrays.copyOfRange(bytes, offset, offset + length); }

    public void copyTo(ByteBuffer output) { output.put(bytes, offset, length); }

    void updateChecksum(java.util.zip.CRC32C checksum) { checksum.update(bytes, offset, length); }
}
