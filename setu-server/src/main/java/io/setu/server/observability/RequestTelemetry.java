package io.setu.server.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.Command;
import io.setu.core.protocol.ErrorCode;

/** Dispatch spans inherit only the current request's remote context, never a worker's ambient span. */
public final class RequestTelemetry implements AutoCloseable {
    private static final TextMapGetter<io.setu.core.protocol.TraceContext> GETTER = (carrier, key) -> switch (key) {
        case "traceparent" -> carrier.traceparent();
        case "tracestate" -> carrier.tracestate();
        default -> null;
    };
    private final Tracer tracer;
    private final java.time.Instant started = java.time.Instant.now();
    private Span span;
    private Scope scope;
    private boolean completed;

    public RequestTelemetry(Tracer tracer) {
        this.tracer = tracer;
    }

    public void command(BrokerRequest request) {
        Context parent = W3CTraceContextPropagator.getInstance().extract(Context.root(), request.traceContext(), GETTER);
        start("setu." + request.command().name(), parent);
        Command command = request.command();
        span.setAttribute("setu.command", command.name());
    }

    private void start(String name, Context parent) {
        span = tracer.spanBuilder(name).setSpanKind(SpanKind.SERVER).setParent(parent)
                .setStartTimestamp(started).startSpan();
        scope = span.makeCurrent();
    }

    public void outcome(ErrorCode error) {
        if (span == null) start("setu.request", Context.root());
        completed = true;
        span.setAttribute("setu.error_code", error.name());
        span.setStatus(error == ErrorCode.NONE ? StatusCode.UNSET : StatusCode.ERROR);
    }

    @Override public void close() {
        try {
            if (!completed) outcome(ErrorCode.INTERNAL_ERROR);
            scope.close();
        }
        finally { span.end(); }
    }
}
