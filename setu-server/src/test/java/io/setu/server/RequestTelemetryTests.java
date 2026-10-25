package io.setu.server;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.setu.core.broker.Broker;
import io.setu.core.broker.BrokerException;
import io.setu.core.protocol.*;
import io.setu.server.dispatcher.BrokerRequestHandler;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class RequestTelemetryTests {
    @Test void acceptsValidRemoteParentsAndIgnoresInvalidContextWithoutLeakingWorkerParents() throws Exception {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var tracer = provider.get("test");
            Broker broker = new Broker() {
                public BrokerResponse execute(BrokerRequest request) { return BrokerResponse.ok(request.correlationId(), request.command()); }
                public void close() { }
            };
            var handler = new BrokerRequestHandler(broker, tracer);
            var request = new BrokerRequest(1, Command.METADATA, null, 0, 0, 0, null, 0, null, null);
            var ambient = tracer.spanBuilder("worker").startSpan();
            try (var scope = ambient.makeCurrent()) {
                for (var trace : java.util.List.of(
                        new TraceContext("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01", "vendor=value"),
                        new TraceContext("00-0123456789abcdef0123456789abcdef-0123456789abcdef-00", "vendor=value"),
                        new TraceContext("00-00000000000000000000000000000000-0123456789abcdef-01", "vendor=value"),
                        new TraceContext("invalid", "vendor=value"), TraceContext.NONE,
                        new TraceContext("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01", "invalid state"))) {
                    handler.handle(ProtocolCodec.encodeRequest(request.withTraceContext(trace)));
                    assertEquals(ambient.getSpanContext(), Span.current().getSpanContext());
                }
            } finally { ambient.end(); }
            var spans = exporter.getFinishedSpanItems();
            var remote = spans.getFirst();
            assertEquals("0123456789abcdef0123456789abcdef", remote.getTraceId());
            assertEquals("0123456789abcdef", remote.getParentSpanId());
            assertTrue(remote.getParentSpanContext().isRemote());
            assertEquals("value", remote.getSpanContext().getTraceState().get("vendor"));
            // The valid unsampled remote parent is not exported by the default parent-based sampler.
            assertEquals(6, spans.size());
            for (int i = 1; i <= 3; i++) assertFalse(spans.get(i).getParentSpanContext().isValid());
            assertEquals(remote.getTraceId(), spans.get(4).getTraceId());
            assertTrue(spans.get(4).getSpanContext().getTraceState().isEmpty());
        }
    }

    @Test void spansDescribeOutcomesWithoutPayloadsAndRestoreTheCallingContext() throws Exception {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var tracer = provider.get("test");
            var outer = tracer.spanBuilder("embedding").startSpan();
            Broker broker = new Broker() {
                public BrokerResponse execute(BrokerRequest request) {
                    assertNotEquals(outer.getSpanContext(), Span.current().getSpanContext());
                    if (request.partition() == 1) throw new BrokerException(ErrorCode.UNKNOWN_TOPIC, "private-topic");
                    return BrokerResponse.ok(request.correlationId(), request.command());
                }
                public void close() { }
            };
            var handler = new BrokerRequestHandler(broker, tracer);
            try (var scope = outer.makeCurrent()) {
                for (int partition = 0; partition < 2; partition++) {
                    var request = new BrokerRequest(partition, Command.PRODUCE, "private-topic", partition,
                            0, 0, "private-payload".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, null, null);
                    var response = ProtocolCodec.decodeResponse(handler.handle(ProtocolCodec.encodeRequest(request)));
                    assertEquals(partition == 0 ? ErrorCode.NONE : ErrorCode.UNKNOWN_TOPIC, response.errorCode());
                    assertEquals(outer.getSpanContext(), Span.current().getSpanContext());
                }
            } finally { outer.end(); }
            var spans = exporter.getFinishedSpanItems();
            for (int i = 0; i < 2; i++) {
                var span = spans.get(i);
                assertEquals("setu.PRODUCE", span.getName());
                assertEquals(SpanKind.SERVER, span.getKind());
                assertFalse(span.getParentSpanContext().isValid());
                assertEquals(i == 0 ? StatusCode.UNSET : StatusCode.ERROR, span.getStatus().getStatusCode());
                assertEquals(i == 0 ? "NONE" : "UNKNOWN_TOPIC", span.getAttributes().get(AttributeKey.stringKey("setu.error_code")));
                assertFalse(span.toString().contains("private-"));
            }
            assertNotEquals(spans.get(0).getTraceId(), spans.get(1).getTraceId());
        }
    }

    @Test void malformedFramesAndIoFailuresEndErrorSpans() throws Exception {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            Broker broker = new Broker() {
                public BrokerResponse execute(BrokerRequest request) throws IOException { throw new IOException("secret-device"); }
                public void close() { }
            };
            var handler = new BrokerRequestHandler(broker, provider.get("test"));
            handler.handle(new byte[0]);
            var request = new BrokerRequest(1, Command.METADATA, null, 0, 0, 0, null, 0, null, null);
            assertEquals(ErrorCode.INTERNAL_ERROR, ProtocolCodec.decodeResponse(handler.handle(ProtocolCodec.encodeRequest(request))).errorCode());
            var spans = exporter.getFinishedSpanItems();
            assertEquals(2, spans.size());
            assertEquals("setu.request", spans.get(0).getName());
            assertTrue(spans.stream().allMatch(span -> span.getStatus().getStatusCode() == StatusCode.ERROR));
            assertTrue(spans.stream().noneMatch(span -> span.toString().contains("secret-device")));
            assertFalse(Span.current().getSpanContext().isValid());
        }
    }
}
