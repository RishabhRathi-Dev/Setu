package io.setu.core.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolCodecTests {
    @Test
    void roundTripsProduceAndStructuredErrorResponses() throws Exception {
        BrokerRequest request = new BrokerRequest(19, Command.PRODUCE, "orders", 2,
                -1, -1, new byte[] { 1, 2, 3 }, 0, null, null);
        BrokerRequest decoded = ProtocolCodec.decodeRequest(ProtocolCodec.encodeRequest(request));
        assertEquals(request.correlationId(), decoded.correlationId());
        assertEquals(request.command(), decoded.command());
        assertEquals(request.topic(), decoded.topic());
        assertEquals(request.partition(), decoded.partition());
        assertArrayEquals(request.value(), decoded.value());

        BrokerResponse error = ProtocolCodec.decodeResponse(ProtocolCodec.errorResponse(
                ProtocolCodec.encodeRequest(request), ErrorCode.QUOTA_EXCEEDED, "quota exceeded"));
        assertEquals(19, error.correlationId());
        assertEquals(Command.PRODUCE, error.command());
        assertEquals(ErrorCode.QUOTA_EXCEEDED, error.errorCode());
        assertEquals("quota exceeded", error.errorMessage());
    }

    @Test
    void rejectsTrailingRequestBytes() throws Exception {
        byte[] valid = ProtocolCodec.encodeRequest(new BrokerRequest(1, Command.METADATA,
                null, -1, -1, -1, null, 0, null, null));
        byte[] malformed = java.util.Arrays.copyOf(valid, valid.length + 1);
        org.junit.jupiter.api.Assertions.assertThrows(ProtocolException.class,
                () -> ProtocolCodec.decodeRequest(malformed));
    }
}
