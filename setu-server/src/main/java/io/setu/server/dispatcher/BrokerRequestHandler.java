package io.setu.server.dispatcher;

import io.setu.core.logging.LogText;
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

/** Decodes protocol commands, invokes the broker API, and encodes structured responses. */
public final class BrokerRequestHandler implements RequestHandler {
    private static final Logger LOG = LoggerFactory.getLogger(BrokerRequestHandler.class);
    private final Broker broker;
    private final io.opentelemetry.api.trace.Tracer tracer;

    public BrokerRequestHandler(Broker broker) {
        this(broker, io.opentelemetry.api.GlobalOpenTelemetry.getTracer("io.setu.server"));
    }

    /** Embedding applications can supply their own SDK's tracer without installing a global. */
    public BrokerRequestHandler(Broker broker, io.opentelemetry.api.trace.Tracer tracer) {
        this.broker = Objects.requireNonNull(broker, "broker");
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    @Override
    public byte[] handle(byte[] payload) {
        return handle(payload, null);
    }

    @Override
    public byte[] handle(io.setu.storage.log.MessageFrame frame) {
        return handle(null, frame);
    }

    private byte[] handle(byte[] payload, io.setu.storage.log.MessageFrame frame) {
        try (var telemetry = new io.setu.server.observability.RequestTelemetry(tracer)) {
            return handle(payload, frame, telemetry);
        }
    }

    private byte[] handle(byte[] payload, io.setu.storage.log.MessageFrame frame,
                          io.setu.server.observability.RequestTelemetry telemetry) {
        int length = frame == null ? payload.length : frame.length();
        BrokerRequest request = null;
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        boolean contextualLogging = LOG.isDebugEnabled();
        String previousCorrelation = contextualLogging ? MDC.get("correlationId") : null;
        try {
            request = frame == null ? ProtocolCodec.decodeRequest(payload) : ProtocolCodec.decodeRequest(frame);
            telemetry.command(request);
            if (LOG.isDebugEnabled()) MDC.put("correlationId", Integer.toString(request.correlationId()));
            if (LOG.isDebugEnabled()) LOG.debug("Request command={} correlation={} topic={} partition={} offset={} group={} member={} frame_bytes={}",
                    request.command(), request.correlationId(), LogText.escape(request.topic()), request.partition(), request.offset(),
                    LogText.escape(request.groupId()), LogText.escape(request.memberId()), length);
            BrokerResponse response = broker.execute(request);
            byte[] encoded = ProtocolCodec.encodeResponse(response);
            telemetry.outcome(response.errorCode());
            if (LOG.isDebugEnabled()) LOG.debug("Reply command={} correlation={} error={} offset={} records={} frame_bytes={} elapsed_us={}",
                    response.command(), response.correlationId(), response.errorCode(), response.offset(),
                    response.records().size(), encoded.length, (System.nanoTime() - started) / 1000);
            return encoded;
        } catch (ProtocolException failure) {
            telemetry.outcome(failure.errorCode());
            LOG.warn("Malformed request frame_bytes={} reason={}", length, LogText.escape(failure.getMessage()));
            return error(payload, frame, failure.errorCode(), failure.getMessage());
        } catch (BrokerException failure) {
            telemetry.outcome(failure.errorCode());
            LOG.warn("Request rejected command={} correlation={} topic={} partition={} error={} reason={}",
                    request == null ? null : request.command(), request == null ? null : request.correlationId(),
                    request == null ? null : LogText.escape(request.topic()), request == null ? null : request.partition(),
                    failure.errorCode(), LogText.escape(failure.getMessage()));
            return error(payload, frame, failure.errorCode(), failure.getMessage());
        } catch (io.setu.core.protocol.BufferBudgetExceededException failure) {
            telemetry.outcome(ErrorCode.QUOTA_EXCEEDED);
            return error(payload, frame, ErrorCode.QUOTA_EXCEEDED, failure.getMessage());
        } catch (IOException failure) {
            telemetry.outcome(ErrorCode.INTERNAL_ERROR);
            logFailure(request, length, failure);
            return error(payload, frame, ErrorCode.INTERNAL_ERROR, "Broker I/O failure");
        } catch (RuntimeException failure) {
            telemetry.outcome(ErrorCode.INTERNAL_ERROR);
            logFailure(request, length, failure);
            return error(payload, frame, ErrorCode.INTERNAL_ERROR, "Internal broker failure");
        } finally {
            if (contextualLogging) {
                if (previousCorrelation == null) MDC.remove("correlationId");
                else MDC.put("correlationId", previousCorrelation);
            }
        }
    }

    private static byte[] error(byte[] payload, io.setu.storage.log.MessageFrame frame,
                                ErrorCode code, String message) {
        return frame == null ? ProtocolCodec.errorResponse(payload, code, message)
                : ProtocolCodec.errorResponse(frame, code, message);
    }

    private static void logFailure(BrokerRequest request, int bytes, Exception failure) {
        LOG.error("Broker request failed command={} correlation={} topic={} partition={} offset={} frame_bytes={}",
                request == null ? null : request.command(), request == null ? null : request.correlationId(),
                request == null ? null : LogText.escape(request.topic()), request == null ? null : request.partition(),
                request == null ? null : request.offset(), bytes, failure);
    }
}
