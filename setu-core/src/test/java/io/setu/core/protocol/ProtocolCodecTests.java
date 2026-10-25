package io.setu.core.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProtocolCodecTests {
    @Test
    void reusableBatchFramesHandleChangingLengthsWithoutOverwritingDecodedRecords() throws Exception {
        var encoder = new ProtocolCodec.ProduceBatchEncoder();
        byte[] first = encoder.encode(1, "orders", 0, java.util.List.of(new byte[]{1, 2, 3}));
        var decoded = ProtocolCodec.decodeRequest(first);
        byte[] second = encoder.encode(2, "orders", 0, java.util.List.of(new byte[]{4, 5, 6}));
        org.junit.jupiter.api.Assertions.assertSame(first, second);
        assertEquals(2, ProtocolCodec.decodeRequest(second).correlationId());
        assertArrayEquals(new byte[]{1, 2, 3}, decoded.valueAt(0));
        for (int size : new int[]{0, 4096, 1048577, 3}) {
            byte[] value = new byte[size];
            java.util.Arrays.fill(value, (byte) 7);
            byte[] frame = encoder.encode(3, "different-topic", 2, java.util.List.of(value));
            assertArrayEquals(ProtocolCodec.encodeProduceBatch(3, "different-topic", 2, java.util.List.of(value)), frame);
            assertArrayEquals(value, ProtocolCodec.decodeRequest(frame).valueAt(0));
        }
        encoder.clear();
        org.junit.jupiter.api.Assertions.assertNotSame(second,
                encoder.encode(4, "orders", 0, java.util.List.of(new byte[]{8, 9, 10})));
    }

    @Test
    void identifierFastPathPreservesTopicRulesAndStrictUtf8() throws Exception {
        for (String topic : java.util.List.of("a", "A-Z_09.test", "x".repeat(249)))
            assertEquals(topic, ProtocolCodec.decodeRequest(ProtocolCodec.encodeProduceBatch(1, topic, 0,
                    java.util.List.of(new byte[0]))).topic());
        for (String topic : java.util.List.of("", ".", "..", "a/b", "a b", "café", "x".repeat(250)))
            org.junit.jupiter.api.Assertions.assertThrows(ProtocolException.class,
                    () -> ProtocolCodec.encodeProduceBatch(1, topic, 0, java.util.List.of(new byte[0])));
        var request = new BrokerRequest(1, Command.JOIN_GROUP, "orders", -1, -1, -1,
                null, 0, "group-é", "reader-₹");
        var decoded = ProtocolCodec.decodeRequest(ProtocolCodec.encodeRequest(request));
        assertEquals(request.groupId(), decoded.groupId());
        assertEquals(request.memberId(), decoded.memberId());
        byte[] invalid = ProtocolCodec.encodeProduceBatch(1, "a", 0, java.util.List.of(new byte[0]));
        invalid[23] = (byte) 0xff;
        org.junit.jupiter.api.Assertions.assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeRequest(invalid));
    }

    private static io.setu.storage.log.MessageValue.Frame owned(byte[] bytes) throws Exception {
        return io.setu.storage.log.MessageValue.Frame.readFrom(
                new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes)), bytes.length);
    }

    @Test
    void ownedFrameReaderEnforcesLimitsAndDistinguishesEofFromTruncation() throws Exception {
        try (var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(new byte[0]))) {
            assertNull(FrameCodec.readOwnedFrame(input, 1024));
        }
        for (byte[] invalid : java.util.List.of(new byte[]{0}, new byte[]{0, 0, 0, 2, 1},
                new byte[]{-1, -1, -1, -1}, new byte[]{0, 0, 4, 1})) {
            try (var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(invalid))) {
                org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                        () -> FrameCodec.readOwnedFrame(input, 1024));
            }
        }
        try (var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(new byte[4]))) {
            assertEquals(0, FrameCodec.readOwnedFrame(input, 0).length());
        }
    }

    @Test
    void requestCursorRejectsTruncatedBatchAndPreservesDefensivePayloadOwnership() throws Exception {
        byte[] encoded = ProtocolCodec.encodeProduceBatch(37, "orders", 0,
                java.util.List.of(new byte[]{1, 2, 3}, new byte[0], new byte[]{4, 5}));
        for (int length = 0; length < encoded.length; length++) {
            byte[] truncated = java.util.Arrays.copyOf(encoded, length);
            var failure = org.junit.jupiter.api.Assertions.assertThrows(ProtocolException.class,
                    () -> ProtocolCodec.decodeRequest(truncated), "prefix length " + length);
            assertEquals(ErrorCode.BAD_REQUEST, failure.errorCode());
            if (length >= 11) assertEquals(37, failure.correlationId());
        }
        var decoded = ProtocolCodec.decodeRequest(encoded);
        java.util.Arrays.fill(encoded, (byte) 99);
        assertArrayEquals(new byte[]{1, 2, 3}, decoded.valueAt(0));
        assertArrayEquals(new byte[]{4, 5}, decoded.valueAt(2));
    }

    @Test
    void directBatchEncodingMatchesCanonicalEncodingAndOwnsItsBytes() throws Exception {
        var values = java.util.List.of(new byte[]{1, 2, 3}, new byte[0], new byte[1024]);
        var request = new BrokerRequest(43, Command.PRODUCE_BATCH, "orders", 2, -1, -1,
                null, 0, null, null, values);
        byte[] encoded = ProtocolCodec.encodeProduceBatch(43, "orders", 2, values);
        assertArrayEquals(ProtocolCodec.encodeRequest(request), encoded);
        values.getFirst()[0] = 99;
        assertArrayEquals(new byte[]{1, 2, 3}, ProtocolCodec.decodeRequest(encoded).valueAt(0));
        org.junit.jupiter.api.Assertions.assertThrows(ProtocolException.class,
                () -> ProtocolCodec.encodeProduceBatch(1, "orders", 0, java.util.List.of()));
        org.junit.jupiter.api.Assertions.assertThrows(ProtocolException.class,
                () -> ProtocolCodec.encodeProduceBatch(1, "bad/topic", 0, values));
        org.junit.jupiter.api.Assertions.assertThrows(ProtocolException.class,
                () -> ProtocolCodec.encodeProduceBatch(1, "orders", -1, values));
        org.junit.jupiter.api.Assertions.assertThrows(ProtocolException.class,
                () -> ProtocolCodec.encodeProduceBatch(1, "orders", 0,
                        java.util.Collections.nCopies(10_001, new byte[0])));
        org.junit.jupiter.api.Assertions.assertThrows(ProtocolException.class,
                () -> ProtocolCodec.encodeProduceBatch(1, "orders", 0,
                        java.util.List.of(new byte[14 * 1024 * 1024 + 1])));
    }

    @Test
    void ownedResponseSlicesAreImmutableAndCopyOnlyTheirValueRange() throws Exception {
        byte[] encoded = ProtocolCodec.encodeResponse(new BrokerResponse(23, Command.FETCH, ErrorCode.NONE,
                "", -1, java.util.List.of(new ProtocolRecord(7, new byte[]{1, 2, 3}),
                        new ProtocolRecord(8, new byte[]{4, 5}), new ProtocolRecord(9, new byte[0])), java.util.Map.of()));
        var frame = owned(encoded);
        var decoded = ProtocolCodec.decodeResponse(frame);
        org.junit.jupiter.api.Assertions.assertThrows(java.nio.ReadOnlyBufferException.class,
                () -> frame.cursor().put(0, (byte) 99));
        org.junit.jupiter.api.Assertions.assertFalse(frame.cursor().hasArray());
        java.util.Arrays.fill(encoded, (byte) 99);
        decoded.records().getFirst().value()[0] = 99;
        assertArrayEquals(new byte[]{1, 2, 3}, decoded.records().getFirst().value());
        var second = decoded.records().get(1).payload();
        var buffer = java.nio.ByteBuffer.allocate(2);
        second.copyTo(buffer);
        assertArrayEquals(new byte[]{4, 5}, buffer.array());
        var output = new java.io.ByteArrayOutputStream();
        second.writeTo(new java.io.DataOutputStream(output));
        assertArrayEquals(buffer.array(), output.toByteArray());
        assertEquals(0, decoded.records().getLast().valueLength());
        assertArrayEquals(ProtocolCodec.encodeResponse(decoded),
                ProtocolCodec.encodeResponse(ProtocolCodec.decodeResponse(frame)));
    }

    @Test
    void responseDecoderRejectsEveryTruncatedPrefixAndTrailingData() throws Exception {
        byte[] encoded = ProtocolCodec.encodeResponse(new BrokerResponse(23, Command.FETCH, ErrorCode.NONE,
                "", -1, java.util.List.of(new ProtocolRecord(7, new byte[]{1, 2, 3})), java.util.Map.of()));
        for (int length = 0; length < encoded.length; length++) {
            byte[] truncated = java.util.Arrays.copyOf(encoded, length);
            var failure = org.junit.jupiter.api.Assertions.assertThrows(ProtocolException.class,
                    () -> ProtocolCodec.decodeResponse(truncated), "prefix length " + length);
            assertEquals(ErrorCode.BAD_REQUEST, failure.errorCode());
            if (length >= 11) assertEquals(23, failure.correlationId());
            var ownedFailure = org.junit.jupiter.api.Assertions.assertThrows(ProtocolException.class,
                    () -> ProtocolCodec.decodeResponse(owned(truncated)), "owned prefix length " + length);
            assertEquals(failure.errorCode(), ownedFailure.errorCode());
            assertEquals(failure.correlationId(), ownedFailure.correlationId());
        }
        org.junit.jupiter.api.Assertions.assertThrows(ProtocolException.class,
                () -> ProtocolCodec.decodeResponse(java.util.Arrays.copyOf(encoded, encoded.length + 1)));
        org.junit.jupiter.api.Assertions.assertThrows(ProtocolException.class,
                () -> ProtocolCodec.decodeResponse(owned(java.util.Arrays.copyOf(encoded, encoded.length + 1))));
    }

    @Test
    void responseDecoderRejectsInvalidCountsLengthsOffsetsAndUtf8() throws Exception {
        byte[] encoded = ProtocolCodec.encodeResponse(new BrokerResponse(23, Command.FETCH, ErrorCode.NONE,
                "", -1, java.util.List.of(new ProtocolRecord(7, new byte[]{1, 2, 3})), java.util.Map.of()));
        for (int count : new int[]{-1, 2, 10_001, Integer.MAX_VALUE}) {
            byte[] invalid = encoded.clone();
            java.nio.ByteBuffer.wrap(invalid).putInt(17, count);
            assertBadResponse(invalid);
        }
        for (int length : new int[]{-1, 4, 16 * 1024 * 1024 + 1}) {
            byte[] invalid = encoded.clone();
            java.nio.ByteBuffer.wrap(invalid).putInt(29, length);
            assertBadResponse(invalid);
        }
        byte[] invalidOffset = encoded.clone();
        java.nio.ByteBuffer.wrap(invalidOffset).putLong(21, -1);
        assertBadResponse(invalidOffset);
        byte[] invalidText = ProtocolCodec.encodeResponse(BrokerResponse.error(23, Command.FETCH,
                ErrorCode.INTERNAL_ERROR, "x"));
        invalidText[17] = (byte) 0xff;
        assertBadResponse(invalidText);
    }

    private static void assertBadResponse(byte[] payload) {
        var failure = org.junit.jupiter.api.Assertions.assertThrows(ProtocolException.class,
                () -> ProtocolCodec.decodeResponse(payload));
        assertEquals(ErrorCode.BAD_REQUEST, failure.errorCode());
        assertEquals(23, failure.correlationId());
        var ownedFailure = org.junit.jupiter.api.Assertions.assertThrows(ProtocolException.class,
                () -> ProtocolCodec.decodeResponse(owned(payload)));
        assertEquals(failure.errorCode(), ownedFailure.errorCode());
        assertEquals(failure.correlationId(), ownedFailure.correlationId());
    }

    @Test
    void responseDecoderPreservesMetadataErrorsAndEmptyCommandBodies() throws Exception {
        var metadata = new BrokerResponse(11, Command.METADATA, ErrorCode.NONE, "caf\u00e9", -1,
                java.util.List.of(), java.util.Map.of("orders", 4, "\u96ea", 2));
        assertEquals(metadata, ProtocolCodec.decodeResponse(ProtocolCodec.encodeResponse(metadata)));
        assertEquals(metadata, ProtocolCodec.decodeResponse(owned(ProtocolCodec.encodeResponse(metadata))));
        for (Command command : Command.values()) {
            var error = BrokerResponse.error(12, command, ErrorCode.QUOTA_EXCEEDED, "caf\u00e9");
            assertEquals(error, ProtocolCodec.decodeResponse(ProtocolCodec.encodeResponse(error)));
            assertEquals(error, ProtocolCodec.decodeResponse(owned(ProtocolCodec.encodeResponse(error))));
            if (command != Command.FETCH && command != Command.METADATA
                    && command != Command.PRODUCE && command != Command.PRODUCE_BATCH
                    && command != Command.JOIN_MANAGED_GROUP && command != Command.GROUP_ASSIGNMENT) {
                var ok = BrokerResponse.ok(13, command);
                assertEquals(ok, ProtocolCodec.decodeResponse(ProtocolCodec.encodeResponse(ok)));
            }
        }
    }

    @Test
    void fetchEncodingMatchesProtocolBytesAndDecodedRecordsOwnTheirPayloads() throws Exception {
        var logRecord = new io.setu.storage.log.LogRecord(5, new byte[]{0, -1, 2});
        var records = java.util.List.of(new ProtocolRecord(5, logRecord.payload()),
                new ProtocolRecord(6, new byte[0]));
        String message = "caf\u00e9";
        BrokerResponse response = new BrokerResponse(17, Command.FETCH, ErrorCode.NONE, message,
                -1, records, java.util.Map.of());
        var expected = new java.io.ByteArrayOutputStream();
        var out = new java.io.DataOutputStream(expected);
        out.writeInt(ProtocolCodec.MAGIC);
        out.writeByte(1);
        out.writeShort(Command.FETCH.id());
        out.writeInt(17);
        out.writeShort(ErrorCode.NONE.id());
        byte[] text = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(text.length);
        out.write(text);
        out.writeInt(2);
        for (ProtocolRecord record : records) {
            out.writeLong(record.offset());
            out.writeInt(record.valueLength());
            out.write(record.value());
        }
        byte[] encoded = ProtocolCodec.encodeResponse(response);
        assertArrayEquals(expected.toByteArray(), encoded);
        var decoded = ProtocolCodec.decodeResponse(encoded);
        java.util.Arrays.fill(encoded, (byte) 99);
        decoded.records().getFirst().value()[0] = 99;
        assertArrayEquals(new byte[]{0, -1, 2}, decoded.records().getFirst().value());
        assertEquals(0, decoded.records().getLast().valueLength());
        assertArrayEquals(expected.toByteArray(), ProtocolCodec.encodeResponse(response));
        assertArrayEquals(new byte[]{0, -1, 2}, logRecord.value());
    }

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
