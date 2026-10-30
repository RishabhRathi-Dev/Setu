# setu-client

This module provides `SetuClient` for synchronous exchanges and `AsyncSetuClient` for bounded asynchronous requests over pooled connections. Both use the Setu protocol with mandatory trace-context fields over plaintext and TLS/mTLS, share codecs with [setu-core](../setu-core/README.md#protocol), and use [setu-security](../setu-security/README.md) for TLS configuration.

## Add to a Maven application

With JDK 26, install the client and its reactor dependencies into your local Maven repository:

```powershell
.\mvnw.cmd -pl setu-client -am install
```

On Linux/macOS, use `bash ./mvnw -pl setu-client -am install`. In your application's `pom.xml`, add:

```xml
<dependency>
    <groupId>org.rishabh</groupId>
    <artifactId>setu-client</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

These coordinates refer to the snapshot installed by the command above. Compile and run your application on JDK 26. A remote client does not open Chronicle partition logs; applications embedding a file-backed broker must also use the [shared JVM options](../config/jvm.options).

## Example

Start the [local broker](../README.md#quick-start), then run this in an application with the client module on its classpath. The example creates or reuses `orders`, joins before producing, appends a batch, and acknowledges the fetched records. It works with both storage modes and can be rerun. For a source-launchable version, use [SetuQuickstart.java](../examples/SetuQuickstart.java).

```java
import io.setu.client.SetuClient;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public class ClientExample {
    public static void main(String[] args) throws Exception {
        String group = "orders-example";
        String member = UUID.randomUUID().toString();
        try (var client = SetuClient.connectPlain("127.0.0.1", 9092,
                5_000, 35_000, 16 * 1024 * 1024)) {
            var created = client.createTopic("orders", 1);
            if (created.errorCode() != ErrorCode.TOPIC_ALREADY_EXISTS) checked(created);
            // Memory members start at the end: join before producing needed records.
            checked(client.joinGroup(group, "orders", member));
            try {
                var appended = checked(client.produceBatch("orders", 0, List.of(
                        "order-1".getBytes(StandardCharsets.UTF_8),
                        "order-2".getBytes(StandardCharsets.UTF_8))));
                var page = checked(client.fetch(group, member, "orders", 0,
                        appended.offset(), 1024));
                if (page.records().size() != 2) throw new IOException("Expected two records");
                for (var record : page.records()) {
                    System.out.println(record.offset() + ": "
                            + new String(record.value(), StandardCharsets.UTF_8));
                }
                checked(client.acknowledge(group, member, "orders", 0,
                        page.records().getLast().offset() + 1));
            } finally {
                checked(client.leaveGroup(group, member));
            }
        }
    }

    private static BrokerResponse checked(BrokerResponse response) throws IOException {
        if (response.errorCode() != ErrorCode.NONE) {
            throw new IOException(response.errorCode() + ": " + response.errorMessage());
        }
        return response;
    }
}
```

The connection factory arguments are host, port, connect timeout, read timeout and maximum frame-body size. Timeouts are milliseconds; zero disables the corresponding socket timeout. Close clients with try-with-resources.

## Requests and ownership

| Method | Result |
| --- | --- |
| `createTopic`, `metadata` | Create a topic or query partition counts |
| `produce`, `produceBatch` | Return the assigned offset or first batch offset |
| `fetch` | Return whole records from a starting offset within a byte budget |
| `joinGroup`, `heartbeat`, `leaveGroup` | Manage explicit membership |
| `acknowledge` | Commit the next offset after processing fetched records |
| `fetchOffset` | Look up a registered member's acknowledged next offset for restart resumption |
| `request` | Send a typed `BrokerRequest` directly |

Every request receives a correlation ID; responses must match both command and correlation. Broker denials return `BrokerResponse.errorCode()` rather than automatically throwing. Socket, framing, decoding and response-matching failures throw exceptions, close the socket, and permanently invalidate the connection. Subsequent calls fail locally; create a new client explicitly. Normal broker error responses keep the connection reusable. The client does not retry or reconnect; a failed produce can still have an uncertain outcome.

Calls on one instance are serialized. Another thread can call `close()` to close the socket and cancel a blocked read or write, including when the read timeout is zero. Codec buffers are cleared under the request lock after socket cancellation. `produceBatch` requires a nonempty batch within the [protocol limits](../setu-core/README.md#protocol); offsets follow list order. Durable acknowledgements follow the broker's [force boundary](../setu-storage/README.md#durability-and-recovery).

Batch encoding copies caller bytes into the final wire frame. Fetched records share immutable slices of their response frame; `value()` returns a defensive copy. Keeping one slice can keep the complete frame alive. Copy values when retaining only a small subset for a long time.

Control replies reuse a private receive buffer capped at 64 KiB per client, released on close. Their decoded strings and metadata remain independent of later replies. Fetch replies retain privately owned frames so records remain valid across subsequent requests and client closure.

## TLS and consumer progress

`connectTls` accepts either an `SSLContext` plus protocol/cipher lists, or a `TlsConfiguration` and `TlsContextFactory`. It performs the handshake and verifies the broker hostname. For the [secure server example](../setu-server/README.md#secure-broker-wiring-example), connect to `localhost:9093` using `client.p12` and `client-trust.p12` from the development certificate scripts. The server trusts the client issuer and the client trusts the broker issuer.

For member-aware consumption:

1. Call `joinGroup(group, topic, member)` before producing records the member needs in memory mode; new memory members start at the current partition end.
2. Call `fetch(group, member, topic, partition, offset, maxBytes[, waitMillis])`.
3. Process the records, then call `acknowledge(group, member, topic, partition, nextOffset)` with the last processed offset plus one.
4. Send `heartbeat(group, member)` while idle, and `leaveGroup(group, member)` when done.

Long-poll waits are 0–30 seconds; the socket read timeout must exceed the wait. On secure servers, member-aware fetches require both topic `CONSUME` and consumer-group `CONSUMER_GROUP_WRITE`; ordinary fetches require only topic consumption permission. Memory storage reclaims according to registered members' progress; file storage retains history unless optional file retention expires it. Expired members must rejoin. See [broker semantics](../setu-core/README.md#broker-semantics).

### Handling an expired file offset

File retention can remove data a consumer has not acknowledged. In the [worked timeline](../docs/operations.md#file-retention-timeline), `orders/0` retains offsets starting at `100`. A request for `0` returns a structured error; `SetuClient` does not throw for this broker response:

```java
import io.setu.client.SetuClient;
import io.setu.core.protocol.ErrorCode;
import java.io.IOException;

try (var client = SetuClient.connectPlain("127.0.0.1", 9092,
        5_000, 30_000, 16 * 1024 * 1024)) {
    var response = client.fetch("orders", 0, 0, 1024);
    if (response.errorCode() == ErrorCode.OFFSET_OUT_OF_RANGE) {
        System.out.println(response.errorMessage());
        // In the worked scenario: "Message retention expired; resume at offset 100".
        // Stop here so the application can review the gap and choose a resume policy.
    } else if (response.errorCode() != ErrorCode.NONE) {
        throw new IOException(response.errorCode() + ": " + response.errorMessage());
    } else {
        System.out.println("Fetched " + response.records().size() + " records");
    }
}
```

After explicitly accepting the lost range in that scenario, resume with `client.fetch("orders", 0, 100, 1024)` on an open client. The value `100` is specific to the scenario; use the boundary reported by the broker for the actual partition. The current protocol includes that boundary in the error message, without a separate numeric field or automatic reset API. Another sweep can advance it before the retry. Applications that require every record should stop and recover the missing data from their own archive instead of skipping the gap. Group-member fetches return the same retention error; rejoining does not restore expired records.

`SetuClient` does not retry, reconnect, pool connections or route partitions. A lost produce response can mean the write succeeded; blindly retrying can duplicate records. `QUOTA_EXCEEDED` indicates admission/policy rejection and may require backoff, a smaller batch or consumer progress.

File memberships survive restart until leave or expiry. Call `fetchOffset(group, member, topic, partition)` and use the response's `offset()` to resume after checking `errorCode()`. Only acknowledged progress recovers; records fetched before restart must be fetched again before acknowledgement. Recovered members receive a fresh inactivity interval. Leave/expiry remove their saved progress; a newly joined file member starts at zero. File retention can still remove needed records.

## Bounded async client

`AsyncSetuClient` returns `CompletableFuture<BrokerResponse>` and uses a fixed number of independent connections. Its constructor takes a `ConnectionFactory`, connection count (1–1,024), bounded queued request count, and read retry count (0–10). The factory can call either `SetuClient.connectPlain` or `connectTls`; TLS hostname verification is preserved. Connections open lazily, reconnect after I/O failure, and close with the async client. Queue saturation completes the returned future exceptionally with `RejectedExecutionException`.

```java
try (var client = new io.setu.client.AsyncSetuClient(
        () -> SetuClient.connectPlain("127.0.0.1", 9092, 5000, 35000, 16 * 1024 * 1024),
        4, 128, 1)) {
    var response = client.produce("orders", "customer-42", new byte[]{1}).join();
    if (response.errorCode() != ErrorCode.NONE) throw new IllegalStateException(response.errorMessage());
    var batch = client.produceBatch("orders", 0, List.of(new byte[]{2}, new byte[]{3})).join();
    if (batch.errorCode() != ErrorCode.NONE) throw new IllegalStateException(batch.errorMessage());
}
```

The keyed overload queries and caches immutable topic partition counts, routing via `floorMod(key.hashCode(), count)`. The explicit partition overload preserves application routing. `produceBatch(topic, partition, values)` appends an ordinary batch and returns its first assigned offset. It snapshots the list and caller bytes before returning, then uses the same reusable batch encoder as `SetuClient` on the assigned connection. It uses default durable acknowledgements and never retries an uncertain write. Configured acknowledgements and other commands remain available through `request(BrokerRequest)`.

Concurrent writes across connections have no submission-order guarantee; await one result before submitting the next when order matters. Public byte-array APIs copy payloads before scheduling. Explicit `produce` and `produceBatch` reserve submission capacity before taking that copy; a full pool rejects them without copying their payloads. Admission counts preparing/running requests plus queued requests, bounded by connection count plus queue capacity. This is a request-count bound, not a payload-byte budget. A caller constructing a `BrokerRequest` owns that construction cost before `request` can admit it.

`METADATA`, `FETCH`, `FETCH_OFFSET`, `GROUP_ASSIGNMENT`, `PRODUCE_IDEMPOTENT` and `TRANSACT` retry I/O failures within the configured count. Identified write retries preserve producer epoch, sequence and contents. Structured broker errors and ordinary/accepted writes are never retried. Cancellation skips a task that has not started; cancelling a future does not undo an active broker operation. `close()` closes sockets and completes running/queued request futures exceptionally. Read timeouts must still exceed long-poll waits.

A slow response or long poll occupies one worker/connection until completion. Other workers can serve queued requests; if every connection is occupied, later requests wait in the bounded FIFO queue or are rejected when full. A cancelled queued task keeps its slot until a worker dequeues and skips it. Applications needing independent long-poll and write latency can use separate client pools. See the [isolated client comparison](../setu-benchmark/README.md#client-pool-and-batch-comparison) for sustained measurements and saturation diagnostics. `SetuClient` continues to serialize exchanges on each connection.

## Managed and transactional APIs

`joinManagedGroup` / `groupAssignment` return generation-bearing assignments; pass the generation to the managed fetch/acknowledgement overloads. `produceIdempotent` takes `ProducerIdentity`; `transact` additionally takes `ConsumerCommit`. `produceBatch(..., AcknowledgementMode)` selects default durable completion or weaker queue admission. FILE-only producer/transaction/group semantics, sequencing responsibilities, retry limits, ACL grants and runnable API examples are in [managed groups and transactions](../docs/transactions.md). `AsyncSetuClient` provides typed idempotent/transactional methods as well as `request` for the other commands.

## Distributed request tracing

Each synchronous wire attempt creates an OpenTelemetry `CLIENT` span under the caller's current context and injects W3C `traceparent`/`tracestate` into the [required request fields](../setu-core/README.md#request-trace-context). The server's `SERVER` span becomes its remote child, allowing caller, client and broker operations to appear in one Tempo trace. Sample flags and valid vendor state propagate. All APIs, including the scratch batch encoder, use this path. Uninstrumented requests still carry both fields with empty values when no valid context exists.

The client includes only the stable OpenTelemetry API. Use the external Java agent or your application's SDK to record/export spans. `client.withTracer(sdk.getTracer("application"))` supplies a tracer without installing a global SDK. If the application only supplies an active valid span context, the no-op client API can forward that parent even without recording its own span. No SDK, exporter threads or vendor backend is installed by the library.

`AsyncSetuClient` captures the submitting context before scheduling, restores it on pooled workers, and keeps keyed metadata lookup and routed writes under that context. Each permitted retry creates a separate client attempt span under the same caller. Request completion/error paths restore the previous context; connections do not retain a request's trace context.

Propagation is mandatory and has no disable option. All requests use the same format. Structured broker errors are never retried. Ordinary writes still have uncertain outcomes and are never retried automatically. Client exception spans do not attach exception messages or payloads.

Explicit `BrokerRequest.withTraceContext(...)` supplies the remote parent of that client's attempt. The client injects its new attempt context rather than forwarding a stale parent span ID. Context strings are bounded; oversized vendor state generated by a local SDK is omitted while retaining the trace parent. Baggage, message-level producer-to-consumer propagation and payload mutation are not part of this transport envelope. See the [LGTM guide](../docs/observability.md).

## Build and verification

For transactional database publishing and deduplicated PostgreSQL effects, see the [outbox/inbox integration](../docs/postgres-outbox.md). Broker publication remains at least once; PostgreSQL inbox keys protect work committed in the same database transaction.

From the repository root with Java 26:

```powershell
.\mvnw.cmd -pl setu-client -am package
.\mvnw.cmd -pl setu-integration-tests -am test
```

Real TCP/mTLS tests and the server's [AOT training](../setu-server/README.md#standalone-jar) exercise client exchanges. The client module has no separate unit-test source tree. Embedding applications using Chronicle storage need the shared [JVM options](../config/jvm.options); an AOT cache belongs to the application's own entry point and classpath.
