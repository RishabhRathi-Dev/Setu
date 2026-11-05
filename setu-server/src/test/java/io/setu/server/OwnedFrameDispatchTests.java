package io.setu.server;

import io.setu.core.broker.Broker;
import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.ProtocolCodec;
import io.setu.core.protocol.Command;
import io.setu.core.protocol.ErrorCode;
import io.setu.security.authentication.AuthenticationResult;
import io.setu.security.authentication.AuthenticationMethod;
import io.setu.security.authorization.AuthorizationDecision;
import io.setu.security.quota.QuotaManager;
import io.setu.server.security.SecuredRequestProcessor;
import io.setu.server.security.ProtocolRequestAuthorizationMetadataResolver;
import io.setu.server.dispatcher.BrokerRequestHandler;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import io.setu.storage.log.MessageFrame;
import io.setu.core.protocol.BufferBudgetExceededException;
import io.setu.core.protocol.BufferBudget;
import io.setu.core.protocol.RequestBuffers;
import io.setu.server.dispatcher.RequestHandler;
import io.setu.server.dispatcher.RequestProcessor;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import static org.junit.jupiter.api.Assertions.*;

class OwnedFrameDispatchTests {
    @Test void builtInSecurityReservesOneDecodeAndDispatchesOnlyAfterAuthorization() throws Exception {
        var encoded = ProtocolCodec.encodeRequest(new BrokerRequest(
                7, Command.PRODUCE_BATCH, "orders", 0, -1, -1, null, 0, null, null,
                Collections.nCopies(32, new byte[]{1, 2})));
        var frame = MessageFrame.readFrom(new DataInputStream(new ByteArrayInputStream(encoded)), encoded.length);
        var dispatched = new AtomicInteger();
        var allow = new AtomicBoolean();
        Broker broker = new Broker() {
            public BrokerResponse execute(BrokerRequest request) {
                dispatched.incrementAndGet();
                assertEquals(32, request.valueCount());
                assertArrayEquals(new byte[]{1, 2}, request.valueAt(31));
                return BrokerResponse.ok(7, request.command());
            }
            public void close() { }
        };
        var processor = new SecuredRequestProcessor(
                (identity, context) -> allow.get() ? AuthorizationDecision.allow()
                        : AuthorizationDecision.deny("denied"),
                new QuotaManager((principal, messages, bytes) -> true),
                new ProtocolRequestAuthorizationMetadataResolver(),
                new BrokerRequestHandler(broker));
        var identity = new AuthenticationResult("alice",
                AuthenticationMethod.MTLS, Map.of());
        for (boolean owned : new boolean[]{false, true}) {
            for (boolean permitted : new boolean[]{false, true}) {
                allow.set(permitted);
                var budget = new BufferBudget(64L * 32);
                try (var credit = budget.reserve(0); var context = RequestBuffers.activate(credit)) {
                    var response = ProtocolCodec.decodeResponse(owned
                            ? processor.process(identity, frame) : processor.process(identity, encoded));
                    assertEquals(permitted ? ErrorCode.NONE
                            : ErrorCode.AUTHORIZATION_DENIED, response.errorCode());
                    assertEquals(64L * 32, budget.usedBytes());
                }
                assertEquals(0, budget.usedBytes());
            }
        }
        assertEquals(2, dispatched.get());
    }

    @Test void legacyHandlersReceivePrivateCopiesAndReserveBeforeAllocatingThem() throws Exception {
        var frame = MessageFrame.readFrom(new DataInputStream(new ByteArrayInputStream(new byte[]{1, 2})), 2);
        RequestHandler handler = bytes -> { bytes[0] = 9; return bytes; };
        assertArrayEquals(new byte[]{9, 2}, RequestProcessor.plaintext(handler).process(null, frame));
        assertArrayEquals(new byte[]{1, 2}, frame.copy());
        RequestProcessor processor = (identity, bytes) -> { bytes[0] = 8; return bytes; };
        assertArrayEquals(new byte[]{8, 2}, processor.process(null, frame));
        assertArrayEquals(new byte[]{1, 2}, frame.copy());
        var budget = new BufferBudget(3);
        try (var credit = budget.reserve(2); var context = RequestBuffers.activate(credit)) {
            assertThrows(BufferBudgetExceededException.class, () -> handler.handle(frame));
            assertThrows(BufferBudgetExceededException.class, () -> processor.process(null, frame));
            assertEquals(2, budget.usedBytes());
        }
        assertEquals(0, budget.usedBytes());
    }
}
