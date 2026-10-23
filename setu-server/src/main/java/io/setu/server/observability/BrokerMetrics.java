package io.setu.server.observability;

import io.setu.core.broker.Broker;
import io.setu.core.broker.BrokerException;
import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.Command;
import io.setu.core.protocol.ErrorCode;
import java.io.IOException;
import java.util.concurrent.atomic.LongAdder;

/** Measures dispatched broker operations; framing and authorization denials are outside this boundary. */
public final class BrokerMetrics implements Broker {
    private static final long[] BOUNDS = {100_000, 500_000, 1_000_000, 5_000_000, 10_000_000, 50_000_000,
            100_000_000, 500_000_000, 1_000_000_000, 5_000_000_000L, 30_000_000_000L};
    private final DefaultBroker broker;
    private final LongAdder[][] outcomes = new LongAdder[Command.values().length][ErrorCode.values().length];
    private final LongAdder[][] buckets = new LongAdder[Command.values().length][BOUNDS.length + 1];
    private final LongAdder[] nanos = new LongAdder[Command.values().length];

    public BrokerMetrics(DefaultBroker broker) {
        this.broker = java.util.Objects.requireNonNull(broker);
        for (int c = 0; c < outcomes.length; c++) {
            nanos[c] = new LongAdder();
            for (int e = 0; e < outcomes[c].length; e++) outcomes[c][e] = new LongAdder();
            for (int b = 0; b < buckets[c].length; b++) buckets[c][b] = new LongAdder();
        }
    }

    @Override public BrokerResponse execute(BrokerRequest request) throws IOException {
        long start = System.nanoTime();
        ErrorCode outcome = ErrorCode.INTERNAL_ERROR;
        try {
            var response = broker.execute(request);
            outcome = response.errorCode();
            return response;
        } catch (BrokerException failure) {
            outcome = failure.errorCode();
            throw failure;
        } finally {
            int command = request.command().ordinal();
            long elapsed = System.nanoTime() - start;
            outcomes[command][outcome.ordinal()].increment();
            nanos[command].add(elapsed);
            int bucket = 0;
            while (bucket < BOUNDS.length && elapsed > BOUNDS[bucket]) bucket++;
            buckets[command][bucket].increment();
        }
    }

    public String scrape() {
        var text = new StringBuilder("# TYPE setu_requests_total counter\n# TYPE setu_request_duration_seconds histogram\n");
        for (Command command : Command.values()) {
            int c = command.ordinal();
            String labels = "command=\"" + command.name() + "\"";
            for (ErrorCode error : ErrorCode.values()) {
                long count = outcomes[c][error.ordinal()].sum();
                if (count != 0) text.append("setu_requests_total{").append(labels).append(",error=\"")
                        .append(error.name()).append("\"} ").append(count).append('\n');
            }
            long cumulative = 0;
            for (int b = 0; b <= BOUNDS.length; b++) {
                cumulative += buckets[c][b].sum();
                text.append("setu_request_duration_seconds_bucket{").append(labels).append(",le=\"")
                        .append(b == BOUNDS.length ? "+Inf" : Double.toString(BOUNDS[b] / 1e9))
                        .append("\"} ").append(cumulative).append('\n');
            }
            text.append("setu_request_duration_seconds_count{").append(labels).append("} ").append(cumulative).append('\n');
            text.append("setu_request_duration_seconds_sum{").append(labels).append("} ").append(nanos[c].sum() / 1e9).append('\n');
        }
        broker.operationalGauges().forEach((name, value) -> {
            String type = name.endsWith("_total") ? "counter" : "gauge";
            text.append("# TYPE setu_").append(name).append(' ').append(type).append('\n');
            text.append("setu_").append(name).append(' ').append(value).append('\n');
        });
        text.append("# TYPE setu_gate_wait_seconds_total counter\nsetu_gate_wait_seconds_total ").append(broker.gateWaitNanos() / 1e9).append('\n');
        text.append("# TYPE setu_recovery_seconds gauge\nsetu_recovery_seconds ").append(broker.recoveryNanos() / 1e9).append('\n');
        text.append("# TYPE setu_ready gauge\nsetu_ready ").append(broker.isReady() ? 1 : 0).append('\n');
        return text.toString();
    }
    public boolean isReady() { return broker.isReady(); }
    /** The embedding application owns the underlying broker. */
    @Override public void close() { }
}
