package io.setu.core.protocol;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TransactionCodecTests {
    @Test void transactionalFieldsAndGenerationExtensionsRoundTripWithoutChangingLegacyRequests() throws Exception {
        var options = new RequestOptions(new ProducerIdentity("producer", 5, 7),
                new ConsumerCommit("group", "member", "input", 2, 99, 10), AcknowledgementMode.DURABLE, -1);
        var request = new BrokerRequest(1, Command.TRANSACT, "output", 3, -1, -1, null, 0, null, null,
                List.of(new byte[]{1, 2})).withOptions(options);
        var decoded = ProtocolCodec.decodeRequest(ProtocolCodec.encodeRequest(request));
        assertEquals(options, decoded.options());
        assertArrayEquals(new byte[]{1, 2}, decoded.valueAt(0));
        for (Command command : new Command[]{Command.FETCH, Command.COMMIT_OFFSET, Command.FETCH_OFFSET}) {
            var grouped = new BrokerRequest(1, command, "input", 2, 99, 1024, null, 0, "group", "member");
            assertEquals(-1, ProtocolCodec.decodeRequest(ProtocolCodec.encodeRequest(grouped)).options().generation());
            assertEquals(10, ProtocolCodec.decodeRequest(ProtocolCodec.encodeRequest(grouped.withGeneration(10)))
                    .options().generation());
        }
    }

    @Test void assignmentsHaveBoundedUniquePartitionOffsetsAndLongGenerations() throws Exception {
        var response = new BrokerResponse(1, Command.GROUP_ASSIGNMENT, ErrorCode.NONE, "", -1, List.of(), Map.of(),
                new GroupAssignment(Long.MAX_VALUE, Map.of(3, Long.MAX_VALUE)));
        assertEquals(response, ProtocolCodec.decodeResponse(ProtocolCodec.encodeResponse(response)));
        byte[] encoded = ProtocolCodec.encodeResponse(response);
        java.nio.ByteBuffer.wrap(encoded).putInt(25, 1025);
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeResponse(encoded));
    }

    @Test void idempotentWritesCannotChooseWeakerAcknowledgementsOrInvalidProducerFields() {
        var request = new BrokerRequest(1, Command.PRODUCE_IDEMPOTENT, "output", 0, -1, -1, null, 0, null, null,
                List.of(new byte[]{1}));
        assertThrows(ProtocolException.class, () -> ProtocolCodec.encodeRequest(request));
        for (var options : List.of(
                new RequestOptions(new ProducerIdentity("producer", -1, 0), null, AcknowledgementMode.DURABLE, -1),
                new RequestOptions(new ProducerIdentity("producer", 0, -1), null, AcknowledgementMode.DURABLE, -1),
                new RequestOptions(new ProducerIdentity("producer", 0, 0), null, AcknowledgementMode.ACCEPTED, -1))) {
            assertThrows(ProtocolException.class, () -> ProtocolCodec.encodeRequest(request.withOptions(options)));
        }
    }

    @Test void unknownAcknowledgementModesAreRejected() throws Exception {
        var request = new BrokerRequest(1, Command.PRODUCE_CONFIGURED, "output", 0, -1, -1, null, 0, null, null,
                List.of(new byte[]{1}));
        byte[] encoded = ProtocolCodec.encodeRequest(request);
        encoded[encoded.length - 1] = 127;
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeRequest(encoded));
    }
}
