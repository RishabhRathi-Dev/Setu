package io.setu.core.protocol;

import io.setu.storage.log.MessageFrame;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TraceContextCodecTests {
    private static final TraceContext TRACE = new TraceContext(
            "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01", "vendor=value");

    @Test void requiredTraceFieldsRoundTripAcrossCommandsAndOptionalFields() throws Exception {
        for (Command command : Command.values()) {
            var options = new RequestOptions(new ProducerIdentity("producer", 0, 0),
                    new ConsumerCommit("group", "member", "input", 0, 1, 2), AcknowledgementMode.DURABLE, -1);
            var request = new BrokerRequest(7, command, "orders", 0, 0, 1024, new byte[]{1}, 1, "group", "member",
                    List.of(new byte[]{2, 3})).withOptions(options);
            if (command == Command.FETCH || command == Command.COMMIT_OFFSET || command == Command.FETCH_OFFSET)
                request = request.withGeneration(2).withWaitMillis(command == Command.FETCH ? 10 : 0);
            byte[] emptyContext = ProtocolCodec.encodeRequest(request);
            byte[] traced = ProtocolCodec.encodeRequest(request.withTraceContext(TRACE));
            assertEquals(0, emptyContext[4]); // Request kind immediately follows magic.
            assertEquals(0, traced[4]);
            int prefix = 8 + TRACE.traceparent().length() + TRACE.tracestate().length();
            assertEquals(0, ByteBuffer.wrap(emptyContext).getInt(11));
            assertEquals(0, ByteBuffer.wrap(emptyContext).getInt(15));
            assertArrayEquals(Arrays.copyOfRange(emptyContext, 19, emptyContext.length), Arrays.copyOfRange(traced, 11 + prefix, traced.length));
            assertEquals(TraceContext.NONE, ProtocolCodec.decodeRequest(emptyContext).traceContext());
            for (BrokerRequest decoded : List.of(ProtocolCodec.decodeRequest(traced), ProtocolCodec.decodeRequest(owned(traced)))) {
                assertEquals(TRACE, decoded.traceContext());
                assertArrayEquals(emptyContext, ProtocolCodec.encodeRequest(decoded.withTraceContext(TraceContext.NONE)));
                assertEquals(TRACE, decoded.withWaitMillis(0).withOptions(decoded.options()).withGeneration(3).traceContext());
            }
        }
    }

    @Test void scratchBatchEncoderSupportsTraceMetadataAndEmptyContextReuse() throws Exception {
        var encoder = new ProduceBatchEncoder();
        var values = List.of(new byte[]{1, 2});
        var request = new BrokerRequest(1, Command.PRODUCE_BATCH, "orders", 0, 0, 0, null, 0, null, null, values);
        byte[] traced = encoder.encode(1, "orders", 0, values, TRACE);
        assertArrayEquals(ProtocolCodec.encodeRequest(request.withTraceContext(TRACE)), traced);
        var decoded = ProtocolCodec.decodeRequest(traced);
        encoder.encode(1, "orders", 0, List.of(new byte[]{9, 9}), TRACE);
        assertArrayEquals(new byte[]{1, 2}, decoded.valueAt(0));
        assertArrayEquals(ProtocolCodec.encodeProduceBatch(1, "orders", 0, values), encoder.encode(1, "orders", 0, values));
    }

    @Test void traceLengthsEncodingTruncationAndTrailingDataAreStrictlyBounded() throws Exception {
        var request = new BrokerRequest(1, Command.METADATA, null, 0, 0, 0, null, 0, null, null).withTraceContext(TRACE);
        byte[] encoded = ProtocolCodec.encodeRequest(request);
        for (int length : new int[]{-1, 513, Integer.MAX_VALUE}) {
            byte[] invalid = encoded.clone();
            ByteBuffer.wrap(invalid).putInt(11, length);
            assertEquals(ErrorCode.BAD_REQUEST, assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeRequest(invalid)).errorCode());
        }
        for (int length = 11; length < encoded.length; length++) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeRequest(truncated));
        }
        byte[] invalidUtf8 = encoded.clone();
        invalidUtf8[15] = (byte) 0xff;
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeRequest(invalidUtf8));
        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeRequest(trailing));
        for (String invalid : List.of("x".repeat(513), "bad\r\nvalue", "é"))
            assertThrows(IllegalArgumentException.class, () -> new TraceContext(invalid, ""));
        assertThrows(IllegalArgumentException.class, () -> new TraceContext("", "vendor=value"));
        byte[] response = ProtocolCodec.encodeResponse(BrokerResponse.ok(1, Command.HEARTBEAT));
        assertEquals(1, response[4]); // Response kind immediately follows magic.
        response[4] = 0;
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeResponse(response)).errorCode());
    }

    @Test void requiresTraceFieldsEvenWithoutAnSdkAndRejectsWrongMessageKinds() throws Exception {
        var request = new BrokerRequest(1, Command.METADATA, null, 0, 0, 0, null, 0, null, null);
        byte[] encoded = ProtocolCodec.encodeRequest(request);
        assertEquals(19, encoded.length);
        assertEquals(TraceContext.NONE, ProtocolCodec.decodeRequest(encoded).traceContext());
        byte[] omitted = Arrays.copyOf(encoded, 11);
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeRequest(omitted)).errorCode());
        for (int kind : new int[]{1, 2, 255}) {
            byte[] invalid = encoded.clone();
            invalid[4] = (byte) kind;
            assertEquals(ErrorCode.BAD_REQUEST, assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeRequest(invalid)).errorCode());
        }
    }

    private static MessageFrame owned(byte[] bytes) throws Exception {
        return MessageFrame.readFrom(new DataInputStream(new ByteArrayInputStream(bytes)), bytes.length);
    }
}
