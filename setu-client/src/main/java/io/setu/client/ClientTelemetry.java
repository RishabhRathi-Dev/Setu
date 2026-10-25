package io.setu.client;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.setu.core.protocol.Command;
import io.setu.core.protocol.ErrorCode;
import io.setu.core.protocol.TraceContext;

/** One serialized wire attempt. Async retries remain children of the submitting context. */
final class ClientTelemetry implements AutoCloseable {
    private static final TextMapGetter<TraceContext> GETTER = (carrier, key) -> switch (key) {
        case "traceparent" -> carrier.traceparent();
        case "tracestate" -> carrier.tracestate();
        default -> null;
    };
    private final Span span;
    private final Scope scope;
    private boolean completed;

    ClientTelemetry(Tracer tracer, Command command, TraceContext supplied) {
        Context parent = supplied.isEmpty() ? Context.current()
                : W3CTraceContextPropagator.getInstance().extract(Context.root(), supplied, GETTER);
        span = tracer.spanBuilder("setu." + command.name()).setSpanKind(SpanKind.CLIENT).setParent(parent)
                .setAttribute("setu.command", command.name()).startSpan();
        scope = span.makeCurrent();
    }

    TraceContext propagation() {
        if (!span.getSpanContext().isValid()) return TraceContext.NONE;
        var fields = new java.util.HashMap<String, String>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), fields, java.util.Map::put);
        String state = fields.getOrDefault("tracestate", "");
        // Oversized vendor state must not make an otherwise valid application request fail.
        if (state.length() > TraceContext.MAX_FIELD_BYTES) state = "";
        return new TraceContext(fields.get("traceparent"), state);
    }

    void outcome(ErrorCode error) {
        completed = true;
        span.setAttribute("setu.error_code", error.name());
        if (error != ErrorCode.NONE) span.setStatus(StatusCode.ERROR);
    }

    @Override public void close() {
        try {
            if (!completed) outcome(ErrorCode.INTERNAL_ERROR);
            scope.close();
        } finally { span.end(); }
    }
}
