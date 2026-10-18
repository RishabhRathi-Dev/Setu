package io.setu.core.protocol;

import io.setu.storage.log.MessageValue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

/** Shared TCP framing: a four-byte big-endian signed length followed by the payload. */
public final class FrameCodec {
    public static final int DEFAULT_MAX_FRAME_SIZE = 16 * 1024 * 1024;
    private FrameCodec() { }

    public static byte[] readFrame(DataInputStream input, int maxFrameSize) throws IOException {
        int length = readLength(input, maxFrameSize);
        if (length < 0) return null;
        byte[] body = new byte[length];
        input.readFully(body);
        return body;
    }

    public static MessageValue.Frame readOwnedFrame(DataInputStream input, int maxFrameSize) throws IOException {
        int length = readLength(input, maxFrameSize);
        return length < 0 ? null : MessageValue.Frame.readFrom(input, length);
    }

    static int readLength(DataInputStream input, int maxFrameSize) throws IOException {
        Objects.requireNonNull(input, "input");
        validate(maxFrameSize);
        int first = input.read();
        if (first < 0) return -1;
        int length = (first << 24) | input.readUnsignedByte() << 16
                | input.readUnsignedByte() << 8 | input.readUnsignedByte();
        if (length < 0 || length > maxFrameSize) throw new IOException("Invalid frame length: " + length);
        return length;
    }

    public static void writeFrame(DataOutputStream output, byte[] body, int maxFrameSize) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(body, "body");
        validate(maxFrameSize);
        if (body.length > maxFrameSize) throw new IOException("Frame exceeds maximum size: " + body.length);
        output.writeInt(body.length);
        output.write(body);
        output.flush();
    }

    private static void validate(int maxFrameSize) {
        if (maxFrameSize < 0) throw new IllegalArgumentException("maxFrameSize must be non-negative");
    }
}
