package io.setu.core.protocol;

import io.setu.storage.log.MessageValue;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OwnedRequestFrameTests {
    private static MessageValue.Frame frame(byte[] bytes) throws Exception {
        return MessageValue.Frame.readFrom(new DataInputStream(new ByteArrayInputStream(bytes)), bytes.length);
    }

    @Test void frameDecoderPreservesWireFormatAndSharesImmutableProducePayloads() throws Exception {
        for (var request : List.of(
                new BrokerRequest(1, Command.PRODUCE, "orders", 0, -1, -1, new byte[]{1, 2}, 0, null, null),
                new BrokerRequest(2, Command.PRODUCE_BATCH, "orders", 0, -1, -1, null, 0, null, null,
                        List.of(new byte[]{1}, new byte[0], new byte[]{2, 3})),
                new BrokerRequest(3, Command.FETCH, "orders", 0, 0, 1024, null, 0, "grüp", "m").withWaitMillis(10))) {
            byte[] encoded = ProtocolCodec.encodeRequest(request);
            var decoded = ProtocolCodec.decodeRequest(frame(encoded));
            assertArrayEquals(encoded, ProtocolCodec.encodeRequest(decoded));
            java.util.Arrays.fill(encoded, (byte) 99);
            if (decoded.command() == Command.PRODUCE) {
                assertArrayEquals(new byte[]{1, 2}, decoded.value());
                assertTrue(decoded.payload().retainedArrayLength() > decoded.valueLength());
            } else if (decoded.command() == Command.PRODUCE_BATCH) {
                assertArrayEquals(new byte[]{2, 3}, decoded.valueAt(2));
                assertTrue(decoded.payloads().getFirst().retainedArrayLength() > 1);
            }
        }
    }

    @Test void malformedFramesAndObjectBudgetRejectionsRemainStructured() throws Exception {
        byte[] valid = ProtocolCodec.encodeProduceBatch(7, "orders", 0, List.of(new byte[]{1, 2, 3}));
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = java.util.Arrays.copyOf(valid, length);
            var expected = assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeRequest(truncated));
            var actual = assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeRequest(frame(truncated)));
            assertEquals(expected.errorCode(), actual.errorCode());
            assertArrayEquals(ProtocolCodec.errorResponse(truncated, actual.errorCode(), actual.getMessage()),
                    ProtocolCodec.errorResponse(frame(truncated), actual.errorCode(), actual.getMessage()));
        }
        var budget = new BufferBudget(100);
        try (var lease = budget.reserve(90); var context = RequestBuffers.activate(lease)) {
            var failure = assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeRequest(frame(valid)));
            assertEquals(ErrorCode.QUOTA_EXCEEDED, failure.errorCode());
            assertEquals(90, budget.usedBytes());
        }
        assertEquals(0, budget.usedBytes());
    }
}
