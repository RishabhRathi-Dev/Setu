package io.setu.storage.log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/** Incremental channel reader; completion permanently removes writable access. */
public final class MessageFrameReader {
    private final byte[] bytes;
    private ByteBuffer buffer;
    public MessageFrameReader(int length) {
        if (length < 0) throw new IllegalArgumentException("negative frame length");
        bytes = new byte[length];
        buffer = ByteBuffer.wrap(bytes);
    }
    public int readFrom(ReadableByteChannel channel) throws IOException {
        if (buffer == null) throw new IllegalStateException("Frame already completed");
        return channel.read(buffer);
    }
    public boolean isComplete() { return buffer != null && !buffer.hasRemaining(); }
    public MessageFrame finish() {
        if (!isComplete()) throw new IllegalStateException("Frame is incomplete or already completed");
        buffer = null;
        return new MessageFrame(bytes);
    }
}
