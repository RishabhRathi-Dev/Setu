package io.setu.server.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

/** Reads and writes Setu's initial wire frame: a 4-byte big-endian length followed by the body. */
public final class FrameCodec {
    public static final int DEFAULT_MAX_FRAME_SIZE = 16 * 1024 * 1024;

    private FrameCodec() { }

    /** Returns {@code null} only when the peer closes cleanly between frames. */
    public static byte[] readFrame(DataInputStream input, int maxFrameSize) throws IOException {
        return io.setu.core.protocol.FrameCodec.readFrame(input, maxFrameSize);
    }

    public static void writeFrame(DataOutputStream output, byte[] body, int maxFrameSize) throws IOException {
        io.setu.core.protocol.FrameCodec.writeFrame(output, body, maxFrameSize);
    }
}
