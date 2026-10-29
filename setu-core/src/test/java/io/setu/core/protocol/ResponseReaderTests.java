package io.setu.core.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResponseReaderTests {
    private static final int LIMIT = FrameCodec.DEFAULT_MAX_FRAME_SIZE;

    private static DataInputStream replies(BrokerResponse... replies) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        for (var reply : replies) FrameCodec.writeFrame(output, ProtocolCodec.encodeResponse(reply), LIMIT);
        return new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    }

    @Test void longerThenShorterControlRepliesKeepIndependentDecodedData() throws Exception {
        var reader = new ResponseReader();
        var metadata = new BrokerResponse(1, Command.METADATA, ErrorCode.NONE, "", -1,
                List.of(), Map.of("orders", 3, "another-topic", 5));
        var error = BrokerResponse.error(2, Command.PRODUCE_BATCH, ErrorCode.BAD_REQUEST, "bad batch");
        var ack = new BrokerResponse(3, Command.PRODUCE_BATCH, ErrorCode.NONE, "", 42, List.of(), Map.of());
        try (var input = replies(metadata, error, ack)) {
            var first = reader.readFrom(input, LIMIT, Command.METADATA);
            var second = reader.readFrom(input, LIMIT, Command.PRODUCE_BATCH);
            assertEquals(ack, reader.readFrom(input, LIMIT, Command.PRODUCE_BATCH));
            reader.clear();
            assertEquals(metadata, first);
            assertEquals(error, second);
            assertNull(reader.readFrom(input, LIMIT, Command.METADATA));
        }
    }

    @Test void retainedFetchPayloadsSurviveLaterReadsAndClear() throws Exception {
        var reader = new ResponseReader();
        var first = new BrokerResponse(1, Command.FETCH, ErrorCode.NONE, "", -1,
                List.of(new ProtocolRecord(0, new byte[]{1, 2, 3})), Map.of());
        var next = new BrokerResponse(2, Command.FETCH, ErrorCode.NONE, "", -1,
                List.of(new ProtocolRecord(1, new byte[]{9, 8, 7})), Map.of());
        // Even an unexpected FETCH on a control exchange must not escape scratch storage.
        for (Command expected : List.of(Command.FETCH, Command.METADATA)) {
            try (var input = replies(first, next)) {
                var retained = reader.readFrom(input, LIMIT, expected);
                reader.readFrom(input, LIMIT, expected);
                reader.clear();
                assertArrayEquals(new byte[]{1, 2, 3}, retained.records().getFirst().value());
                if (expected == Command.FETCH)
                    assertTrue(retained.records().getFirst().payload().retainedArrayLength() > 3);
            }
        }
    }

    @Test void largeMetadataFallsBackToPrivateFrame() throws Exception {
        var topics = new java.util.LinkedHashMap<String, Integer>();
        for (int i = 0; i < 3000; i++) topics.put("topic-with-long-name-" + i, 1);
        var response = new BrokerResponse(1, Command.METADATA, ErrorCode.NONE, "", -1, List.of(), topics);
        assertTrue(ProtocolCodec.encodeResponse(response).length > 64 * 1024);
        var reader = new ResponseReader();
        try (var input = replies(response, BrokerResponse.ok(2, Command.HEARTBEAT))) {
            var retained = reader.readFrom(input, LIMIT, Command.METADATA);
            reader.readFrom(input, LIMIT, Command.HEARTBEAT);
            assertEquals(response, retained);
        }
    }

    @Test void rejectsInvalidLengthsTruncationAndTrailingBytes() throws Exception {
        var reader = new ResponseReader();
        for (int length : new int[]{-1, LIMIT + 1}) {
            byte[] header = java.nio.ByteBuffer.allocate(4).putInt(length).array();
            assertThrows(IOException.class, () -> reader.readFrom(
                    new DataInputStream(new ByteArrayInputStream(header)), LIMIT, Command.METADATA));
        }
        assertThrows(EOFException.class, () -> reader.readFrom(
                new DataInputStream(new ByteArrayInputStream(new byte[]{0, 0})), LIMIT, Command.METADATA));
        assertThrows(EOFException.class, () -> reader.readFrom(
                new DataInputStream(new ByteArrayInputStream(new byte[]{0, 0, 0, 3, 1})), LIMIT, Command.METADATA));
        byte[] payload = ProtocolCodec.encodeResponse(BrokerResponse.ok(1, Command.HEARTBEAT));
        var bytes = new ByteArrayOutputStream();
        FrameCodec.writeFrame(new DataOutputStream(bytes), java.util.Arrays.copyOf(payload, payload.length + 1), LIMIT);
        assertThrows(ProtocolException.class, () -> reader.readFrom(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), LIMIT, Command.HEARTBEAT));
    }
}
