package io.setu.core.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProtocolCodecTests {
    @Test
    void batchAndFetchEncodingPreserveOwnedPayloadsWhenCallersMutateCopies() throws Exception {
        byte[] input = {1, 2, 3};
        BrokerRequest batch = new BrokerRequest(12, Command.PRODUCE_BATCH, "orders", 0,
                -1, -1, null, 0, null, null, java.util.List.of(input));
        input[0] = 9;
        batch.values().getFirst()[1] = 9;
        batch.valueAt(0)[2] = 9;
        assertEquals(3, batch.valueLengthAt(0));
        byte[] encoded = ProtocolCodec.encodeRequest(batch);
        assertArrayEquals(new byte[]{1, 2, 3}, ProtocolCodec.decodeRequest(encoded).valueAt(0));
        assertArrayEquals(encoded, ProtocolCodec.encodeRequest(batch));

        byte[] fetched = {4, 5, 6};
        ProtocolRecord record = new ProtocolRecord(7, fetched);
        fetched[0] = 9;
        record.value()[1] = 9;
        BrokerResponse response = new BrokerResponse(13, Command.FETCH, ErrorCode.NONE, "", -1,
                java.util.List.of(record), java.util.Map.of());
        assertEquals(3, record.valueLength());
        assertArrayEquals(new byte[]{4, 5, 6}, ProtocolCodec.decodeResponse(
                ProtocolCodec.encodeResponse(response)).records().getFirst().value());
        assertArrayEquals(new byte[]{4, 5, 6}, record.value());
    }

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

    @Test
    void roundTripsMemberAwareFetchAndAcknowledgementWhilePreservingLegacyBodies() throws Exception {
        BrokerRequest groupedFetch = new BrokerRequest(2, Command.FETCH, "orders", 1,
                7, 4096, null, 0, "workers", "member-a");
        BrokerRequest decodedFetch = ProtocolCodec.decodeRequest(ProtocolCodec.encodeRequest(groupedFetch));
        assertEquals("workers", decodedFetch.groupId());
        assertEquals("member-a", decodedFetch.memberId());

        BrokerRequest acknowledgement = new BrokerRequest(3, Command.COMMIT_OFFSET, "orders", 1,
                9, 0, null, 0, "workers", "member-a");
        BrokerRequest decodedAck = ProtocolCodec.decodeRequest(ProtocolCodec.encodeRequest(acknowledgement));
        assertEquals(9, decodedAck.offset());
        assertEquals("member-a", decodedAck.memberId());

        BrokerRequest legacyFetch = new BrokerRequest(4, Command.FETCH, "orders", 0,
                0, 1024, null, 0, null, null);
        BrokerRequest decodedLegacy = ProtocolCodec.decodeRequest(ProtocolCodec.encodeRequest(legacyFetch));
        assertNull(decodedLegacy.groupId());
        assertNull(decodedLegacy.memberId());
    }
}
