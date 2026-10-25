package io.setu.integration;

import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.setu.client.AsyncSetuClient;
import io.setu.client.SetuClient;
import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.*;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.BrokerRequestHandler;
import io.setu.server.tcp.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TracePropagationIntegrationTests {
    private static SetuClient connect(int port) throws java.io.IOException {
        return SetuClient.connectPlain("127.0.0.1", port, 3000, 3000, 1024 * 1024);
    }

    @Test void uninstrumentedClientStillSendsTheRequiredContextFieldsForRegularAndBatchRequests() throws Exception {
        try (var listener = new ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress());
             var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var peer = worker.submit(() -> {
                try (var socket = listener.accept()) {
                    for (int i = 0; i < 2; i++) {
                        byte[] encoded = FrameCodec.readFrame(new DataInputStream(socket.getInputStream()), 1024 * 1024);
                        assertEquals(0, encoded[4]);
                        assertEquals(0, java.nio.ByteBuffer.wrap(encoded).getInt(11));
                        assertEquals(0, java.nio.ByteBuffer.wrap(encoded).getInt(15));
                        var request = ProtocolCodec.decodeRequest(encoded);
                        assertEquals(TraceContext.NONE, request.traceContext());
                        var response = new BrokerResponse(request.correlationId(), request.command(), ErrorCode.NONE,
                                "", 0, List.of(), java.util.Map.of());
                        FrameCodec.writeFrame(new DataOutputStream(socket.getOutputStream()), ProtocolCodec.encodeResponse(response), 1024 * 1024);
                    }
                }
                return true;
            });
            try (var scope = Context.root().makeCurrent();
                 var client = connect(listener.getLocalPort()).withTracer(io.opentelemetry.api.OpenTelemetry.noop().getTracer("test"))) {
                assertEquals(ErrorCode.NONE, client.metadata().errorCode());
                assertEquals(ErrorCode.NONE, client.produceBatch("orders", 0, List.of(new byte[]{1})).errorCode());
                assertTrue(peer.get(5, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void clientAndServerSpansShareTraceStateAndCorrectParentsAcrossBothTransports(boolean select) throws Exception {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
             var broker = new DefaultBroker(1024)) {
            var tracer = provider.get("test");
            broker.execute(new BrokerRequest(0, Command.CREATE_TOPIC, "orders", 0, 0, 0, null, 1, null, null));
            broker.execute(new BrokerRequest(0, Command.JOIN_GROUP, "orders", 0, 0, 0, null, 0, "group", "member"));
            var handler = new BrokerRequestHandler(broker, tracer);
            var address = new InetSocketAddress("127.0.0.1", 0);
            var blocking = select ? null : new TcpServer(address, new FramedConnectionHandler(handler));
            var selector = select ? new SelectorTcpServer(address, 1024 * 1024, TcpServerOptions.defaults(), handler) : null;
            try (AutoCloseable server = select ? selector : blocking) {
                if (select) selector.start(); else blocking.start();
                int port = (select ? selector.localAddress() : blocking.localAddress()).getPort();
                try (var client = connect(port).withTracer(tracer)) {
                    var remote = SpanContext.createFromRemoteParent("0123456789abcdef0123456789abcdef", "0123456789abcdef",
                            TraceFlags.getSampled(), TraceState.builder().put("vendor", "value").build());
                    var caller = tracer.spanBuilder("application").setParent(Context.root().with(Span.wrap(remote))).startSpan();
                    try (var scope = caller.makeCurrent()) {
                        assertEquals(ErrorCode.NONE, client.produceBatch("orders", 0, List.of(new byte[]{1}, new byte[]{2})).errorCode());
                        assertEquals(ErrorCode.NONE, client.produce("orders", 0, new byte[]{3}).errorCode());
                        assertEquals(3, client.fetch("orders", 0, 0, 1024).records().size());
                        assertEquals(ErrorCode.UNKNOWN_TOPIC, client.fetch("missing", 0, 0, 1024).errorCode());
                        assertEquals(caller.getSpanContext(), Span.current().getSpanContext());
                    } finally { caller.end(); }
                    var spans = exporter.getFinishedSpanItems();
                    var clients = spans.stream().filter(span -> span.getKind() == SpanKind.CLIENT).toList();
                    var servers = spans.stream().filter(span -> span.getKind() == SpanKind.SERVER).toList();
                    assertEquals(4, clients.size());
                    assertEquals(4, servers.size());
                    for (var span : clients) {
                        assertEquals(caller.getSpanContext().getSpanId(), span.getParentSpanId());
                        var serverSpan = servers.stream().filter(s -> s.getParentSpanId().equals(span.getSpanId())).findFirst().orElseThrow();
                        assertEquals(span.getTraceId(), serverSpan.getTraceId());
                        assertTrue(serverSpan.getParentSpanContext().isRemote());
                        assertEquals("value", serverSpan.getSpanContext().getTraceState().get("vendor"));
                    }
                    assertEquals(StatusCode.ERROR, servers.getLast().getStatus().getStatusCode());
                    exporter.reset();
                    var next = tracer.spanBuilder("next-caller").startSpan();
                    try (var scope = next.makeCurrent()) {
                        client.metadata();
                        assertEquals(next.getSpanContext(), Span.current().getSpanContext());
                        client.metadata();
                    } finally { next.end(); }
                    var following = exporter.getFinishedSpanItems().stream().filter(s -> s.getKind() == SpanKind.SERVER).toList();
                    assertEquals(next.getSpanContext().getTraceId(), following.getFirst().getTraceId());
                    assertEquals(next.getSpanContext().getTraceId(), following.getLast().getTraceId());
                    assertNotEquals(following.getFirst().getParentSpanId(), following.getLast().getParentSpanId());
                }
            }
        }
    }

    @Test void asyncQueuedKeyRoutingRetainsEachSubmittingContextAfterCallerScopesClose() throws Exception {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
             var broker = new DefaultBroker(1024)) {
            var tracer = provider.get("test");
            broker.execute(new BrokerRequest(0, Command.CREATE_TOPIC, "orders", 0, 0, 0, null, 2, null, null));
            try (var server = new TcpServer(new InetSocketAddress("127.0.0.1", 0),
                    new FramedConnectionHandler(new BrokerRequestHandler(broker, tracer)))) {
                server.start();
                var releaseConnections = new CountDownLatch(1);
                try (var client = new AsyncSetuClient(() -> {
                    try { if (!releaseConnections.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("test gate timed out"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
                    return connect(server.localAddress().getPort()).withTracer(tracer);
                }, 2, 8, 1)) {
                    var first = tracer.spanBuilder("first").startSpan();
                    var second = tracer.spanBuilder("second").startSpan();
                    CompletableFuture<BrokerResponse> a, b;
                    try {
                        try (var scope = first.makeCurrent()) { a = client.produce("orders", "first-key", new byte[]{1}); }
                        try (var scope = second.makeCurrent()) { b = client.produce("orders", "second-key", new byte[]{2}); }
                        releaseConnections.countDown();
                        assertEquals(ErrorCode.NONE, a.get(5, TimeUnit.SECONDS).errorCode());
                        assertEquals(ErrorCode.NONE, b.get(5, TimeUnit.SECONDS).errorCode());
                        assertFalse(Span.current().getSpanContext().isValid());
                    } finally { releaseConnections.countDown(); first.end(); second.end(); }
                    var spans = exporter.getFinishedSpanItems();
                    for (var caller : List.of(first, second)) {
                        var children = spans.stream().filter(s -> s.getKind() == SpanKind.CLIENT
                                && s.getParentSpanId().equals(caller.getSpanContext().getSpanId())).toList();
                        assertEquals(2, children.size()); // Metadata plus the routed produce, both under the original caller.
                        assertEquals(2, spans.stream().filter(s -> s.getKind() == SpanKind.SERVER
                                && s.getTraceId().equals(caller.getSpanContext().getTraceId())).count());
                    }
                }
            }
        }
    }

    @Test void readRetryCreatesSeparateAttemptsUnderTheCapturedCallerWithoutRetryingBrokerDenials() throws Exception {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
             var listener = new ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress());
             var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var tracer = provider.get("test");
            var opened = new AtomicInteger();
            var peer = worker.submit(() -> {
                var contexts = new java.util.ArrayList<TraceContext>();
                for (int attempt = 0; attempt < 2; attempt++) {
                    try (var socket = listener.accept()) {
                        var request = ProtocolCodec.decodeRequest(FrameCodec.readFrame(new DataInputStream(socket.getInputStream()), 1024 * 1024));
                        contexts.add(request.traceContext());
                        if (attempt == 1) FrameCodec.writeFrame(new DataOutputStream(socket.getOutputStream()),
                                ProtocolCodec.encodeResponse(BrokerResponse.error(request.correlationId(), request.command(),
                                        ErrorCode.BAD_REQUEST, "request rejected")), 1024 * 1024);
                    }
                }
                return contexts;
            });
            try (var client = new AsyncSetuClient(() -> {
                opened.incrementAndGet();
                return connect(listener.getLocalPort()).withTracer(tracer);
            }, 1, 8, 2)) {
                var caller = tracer.spanBuilder("retrying-caller").startSpan();
                try {
                    CompletableFuture<BrokerResponse> future;
                    try (var scope = caller.makeCurrent()) {
                        future = client.request(new BrokerRequest(1, Command.METADATA, null, 0, 0, 0, null, 0, null, null));
                    }
                    assertEquals(ErrorCode.BAD_REQUEST, future.get(5, TimeUnit.SECONDS).errorCode());
                    var contexts = peer.get(5, TimeUnit.SECONDS);
                    assertEquals(2, opened.get());
                    assertNotEquals(contexts.getFirst().traceparent(), contexts.getLast().traceparent());
                    assertTrue(contexts.stream().allMatch(c -> c.traceparent().contains(caller.getSpanContext().getTraceId())));
                    var attempts = exporter.getFinishedSpanItems().stream().filter(s -> s.getKind() == SpanKind.CLIENT).toList();
                    assertEquals(2, attempts.size());
                    assertTrue(attempts.stream().allMatch(s -> s.getParentSpanId().equals(caller.getSpanContext().getSpanId())));
                } finally { caller.end(); }
            }
        }
    }
}
