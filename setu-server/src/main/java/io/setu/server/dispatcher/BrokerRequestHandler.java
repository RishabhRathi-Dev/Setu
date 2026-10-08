package io.setu.server.dispatcher;

import io.setu.core.broker.Broker;
import io.setu.core.broker.BrokerException;
import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.ErrorCode;
import io.setu.core.protocol.ProtocolCodec;
import io.setu.core.protocol.ProtocolException;

import java.io.IOException;
import java.util.Objects;

/** Decodes protocol v1 commands, invokes the broker API, and encodes structured responses. */
public final class BrokerRequestHandler implements RequestHandler {
    private final Broker broker;

    public BrokerRequestHandler(Broker broker) {
        this.broker = Objects.requireNonNull(broker, "broker");
    }

    @Override
    public byte[] handle(byte[] payload) {
        try {
            BrokerRequest request = ProtocolCodec.decodeRequest(payload);
            BrokerResponse response = broker.execute(request);
            return ProtocolCodec.encodeResponse(response);
        } catch (ProtocolException failure) {
            return ProtocolCodec.errorResponse(payload, failure.errorCode(), failure.getMessage());
        } catch (BrokerException failure) {
            return ProtocolCodec.errorResponse(payload, failure.errorCode(), failure.getMessage());
        } catch (IOException failure) {
            return ProtocolCodec.errorResponse(payload, ErrorCode.INTERNAL_ERROR, "Broker I/O failure");
        } catch (RuntimeException failure) {
            return ProtocolCodec.errorResponse(payload, ErrorCode.INTERNAL_ERROR, "Internal broker failure");
        }
    }
}
