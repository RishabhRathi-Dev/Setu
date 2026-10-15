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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Decodes protocol v1 commands, invokes the broker API, and encodes structured responses. */
public final class BrokerRequestHandler implements RequestHandler {
    private static final Logger LOG = LoggerFactory.getLogger(BrokerRequestHandler.class);
    private final Broker broker;

    public BrokerRequestHandler(Broker broker) {
        this.broker = Objects.requireNonNull(broker, "broker");
    }

    @Override
    public byte[] handle(byte[] payload) {
        return handle(payload, null);
    }

    @Override
    public byte[] handle(io.setu.storage.log.MessageValue.Frame frame) {
        return handle(null, frame);
    }

    private byte[] handle(byte[] payload, io.setu.storage.log.MessageValue.Frame frame) {
        int length = frame == null ? payload.length : frame.length();
        BrokerRequest request = null;
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        boolean contextualLogging = LOG.isDebugEnabled();
        String previousCorrelation = contextualLogging ? MDC.get("correlationId") : null;
        try {
            request = frame == null ? ProtocolCodec.decodeRequest(payload) : ProtocolCodec.decodeRequest(frame);
            if (LOG.isDebugEnabled()) MDC.put("correlationId", Integer.toString(request.correlationId()));
            if (LOG.isDebugEnabled()) LOG.debug("Request command={} correlation={} topic={} partition={} offset={} group={} member={} frame_bytes={}",
                    request.command(), request.correlationId(), request.topic(), request.partition(), request.offset(),
                    request.groupId(), request.memberId(), length);
            BrokerResponse response = broker.execute(request);
            byte[] encoded = ProtocolCodec.encodeResponse(response);
            if (LOG.isDebugEnabled()) LOG.debug("Reply command={} correlation={} error={} offset={} records={} frame_bytes={} elapsed_us={}",
                    response.command(), response.correlationId(), response.errorCode(), response.offset(),
                    response.records().size(), encoded.length, (System.nanoTime() - started) / 1000);
            return encoded;
        } catch (ProtocolException failure) {
            LOG.warn("Malformed request frame_bytes={} reason={}", length, failure.getMessage());
            return error(payload, frame, failure.errorCode(), failure.getMessage());
        } catch (BrokerException failure) {
            LOG.warn("Request rejected command={} correlation={} topic={} partition={} error={} reason={}",
                    request == null ? null : request.command(), request == null ? null : request.correlationId(),
                    request == null ? null : request.topic(), request == null ? null : request.partition(),
                    failure.errorCode(), failure.getMessage());
            return error(payload, frame, failure.errorCode(), failure.getMessage());
        } catch (io.setu.core.protocol.BufferBudget.Exhausted failure) {
            return error(payload, frame, ErrorCode.QUOTA_EXCEEDED, failure.getMessage());
        } catch (IOException failure) {
            logFailure(request, length, failure);
            return error(payload, frame, ErrorCode.INTERNAL_ERROR, "Broker I/O failure");
        } catch (RuntimeException failure) {
            logFailure(request, length, failure);
            return error(payload, frame, ErrorCode.INTERNAL_ERROR, "Internal broker failure");
        } finally {
            if (contextualLogging) {
                if (previousCorrelation == null) MDC.remove("correlationId");
                else MDC.put("correlationId", previousCorrelation);
            }
        }
    }

    private static byte[] error(byte[] payload, io.setu.storage.log.MessageValue.Frame frame,
                                ErrorCode code, String message) {
        return frame == null ? ProtocolCodec.errorResponse(payload, code, message)
                : ProtocolCodec.errorResponse(frame, code, message);
    }

    private static void logFailure(BrokerRequest request, int bytes, Exception failure) {
        LOG.error("Broker request failed command={} correlation={} topic={} partition={} offset={} frame_bytes={}",
                request == null ? null : request.command(), request == null ? null : request.correlationId(),
                request == null ? null : request.topic(), request == null ? null : request.partition(),
                request == null ? null : request.offset(), bytes, failure);
    }
}
