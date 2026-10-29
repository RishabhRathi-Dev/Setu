package io.setu.core.protocol;

import io.setu.storage.log.MessageFrame;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Receive buffer for serialized client exchanges. Control replies decode to independent
 * objects; fetch replies keep private frames because their payload slices can escape.
 */
public final class ResponseReader {
    private static final int MAX_RETAINED_BYTES = 64 * 1024;
    private byte[] buffer;
    private int frameLength;

    public BrokerResponse readFrom(DataInputStream input, int maxFrameSize, Command expectedCommand) throws IOException {
        java.util.Objects.requireNonNull(expectedCommand, "expectedCommand");
        int length = FrameCodec.readLength(input, maxFrameSize);
        frameLength = length;
        if (length < 0) return null;
        if (expectedCommand == Command.FETCH || length > MAX_RETAINED_BYTES)
            return ProtocolCodec.decodeResponse(MessageFrame.readFrom(input, length));
        if (buffer == null || buffer.length < length)
            buffer = new byte[Math.min(MAX_RETAINED_BYTES, Math.max(256, length))];
        input.readFully(buffer, 0, length);
        // Bound the cursor to this reply, excluding bytes left by a longer previous reply.
        // The copying decoder also handles an unexpected FETCH safely before the client
        // rejects its command: none of its records can reference the scratch buffer.
        ByteBuffer cursor = ByteBuffer.wrap(buffer, 0, length);
        int correlation = length >= ProtocolCodec.HEADER_BYTES ? cursor.getInt(ProtocolCodec.CORRELATION_OFFSET) : -1;
        return ProtocolCodec.decodeResponse(cursor, correlation, null);
    }

    public int frameLength() { return frameLength; }
    public void clear() { buffer = null; }
}
