# setu-core

`setu-core` owns the single-process broker and protocol types shared by client and server. It uses [setu-storage](../setu-storage/README.md) for partition logs; transport and security enforcement belong to [setu-server](../setu-server/README.md).

## Broker usage

```java
import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.Command;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

try (var broker = new DefaultBroker(Path.of("data"), 1024 * 1024)) {
    broker.execute(new BrokerRequest(1, Command.CREATE_TOPIC, "orders", -1,
            -1, -1, null, 1, null, null));
    var result = broker.execute(new BrokerRequest(2, Command.PRODUCE, "orders", 0,
            -1, -1, "order-1".getBytes(StandardCharsets.UTF_8), 0, null, null));
    System.out.println(result.offset());
}
```

`DefaultBroker(Path, maxRecordBytes)` selects durable Chronicle Queue storage. `DefaultBroker(maxRecordBytes)` selects volatile memory. Constructors also accept an explicit `StorageMode`, queue mapping block size, consumer deadlines, retained-memory budget, optional lossy memory expiry and file retention. File use requires the shared [JVM options](../config/jvm.options).

Direct broker calls bypass network authentication, ACLs and quotas. `BrokerException` carries application errors; I/O failures propagate to the embedding application. The server translates these into wire responses.

### File retention example

This embedded broker expires completed daily files seven days after their cycle end. Its maintenance task handles existing and new partitions; the application owns the broker's lifetime.

```java
import io.setu.core.broker.DefaultBroker;
import io.setu.storage.log.MessageLogs;
import io.setu.storage.log.StorageMode;
import java.nio.file.Path;
import java.time.Duration;

long fileRetentionMillis = Duration.ofDays(7).toMillis();
try (var broker = new DefaultBroker(
        StorageMode.FILE,
        Path.of("data"),
        1024 * 1024, // maximum record payload
        MessageLogs.DEFAULT_QUEUE_BLOCK_BYTES,
        DefaultBroker.DEFAULT_ACK_TIMEOUT_MILLIS,
        DefaultBroker.DEFAULT_CONSUMER_INACTIVITY_TIMEOUT_MILLIS,
        DefaultBroker.DEFAULT_MAX_RETAINED_MEMORY_BYTES,
        0, // timed memory expiry disabled; memory settings are unused in FILE mode
        fileRetentionMillis)) {
    // Execute broker requests or run the application's server here.
    // Keep this scope open for the entire server lifetime.
}
```

Use `0` for the final argument to disable file expiry. The newest populated daily file always remains, and expiry does not wait for consumers to acknowledge records. See the [worked timeline](../docs/operations.md#file-retention-timeline) for precise offset behavior.

## Broker semantics

| Concern | Current behavior |
| --- | --- |
| Topics | ASCII letters, digits, `.`, `_`, `-`; 1–249 characters; `.` and `..` rejected |
| Limits | Up to 10,000 topics and 1–1,024 contiguous partitions per topic |
| Offsets | Dense, zero-based record offsets within each partition; batch produce returns the first offset |
| Fetch | Whole records within a payload-byte budget; an oversized first record yields an empty page |
| Long polling | Optional wait of 0–30,000 ms; append, timeout, eviction or shutdown wakes the request |
| Membership | Explicit join, leave, heartbeat and next-offset acknowledgement; default 10,000 members / 100,000 summed partition memberships |
| Expiry | Default 30-second acknowledgement / 60-second inactivity deadlines; maintenance runs every 10 ms |
| Restart | File topics, records, active memberships and acknowledged progress recover; memory state is lost |

Memory members start at the partition end when they join. Acknowledgements reclaim through the lowest registered member's next offset; leaving or expiry removes that member's retention claim. File storage keeps full history by default. Optional `fileRetentionMillis` on the extended constructor expires completed daily files independently of consumer progress; fetches before the retained boundary return `OFFSET_OUT_OF_RANGE` with a resume offset. File topic paths are normalized and checked against the configured data directory; topic/partition symlinks that escape their parent are rejected. Explicit membership does not assign partitions; FILE managed membership adds automatic assignment and generation fencing. New joins exceeding either metadata limit return `QUOTA_EXCEEDED` before allocation. Rejoining an existing member remains possible at capacity; leave and expiry release active membership capacity. The extended constructor accepts `ConsumerMetadataLimits`; group/member IDs are limited to 4 KiB UTF-8. `activeConsumerCount()` and `activeConsumerPartitionMemberships()` expose current usage. A `COMMIT_OFFSET` without a member validates the partition boundary and returns success without storing an offset. Explicit member progress lasts only for the membership; managed group offsets remain after departure. `FETCH_OFFSET` returns a registered member's next acknowledged offset.

File brokers force join, changed acknowledgement, leave and expiry events to a separate Chronicle journal in `@consumer-state/` before publishing the change. Startup replays the journal after recovering partitions; invalid committed metadata fails startup. Recovered members receive a fresh inactivity interval, and only acknowledged progress is restored: pending fetched records must be fetched again before acknowledging them. Heartbeats and fetched positions are not journaled. The journal also contains complete idempotent/transactional requests, is not subject to topic retention, and is compacted through [metadata checkpoints](../docs/journal-checkpoints.md); include the entire journal directory in backups and monitor disk use. Leave and expiry delete explicit membership progress, while managed group offsets remain. Fresh groups/file members still start at zero and can encounter a retained-history gap.

Memory brokers share a 256 MiB retained-log budget by default. Whole-batch admission failure returns `QUOTA_EXCEEDED` without consuming offsets. Zero disables that budget. Protocol buffers and file indexes are outside it. See [storage ownership and admission](../setu-storage/README.md).

Memory fetches use immutable snapshots outside the membership/long-poll lock. Durable storage serializes native I/O, while membership, acknowledgement and waiter coordination retain locks. CAS publication does not make the entire broker lock-free.

## Protocol

`FrameCodec` adds a signed, big-endian four-byte body length. `ProtocolCodec` encodes an 11-byte body header: `SETU` magic (4 bytes), kind (1), command ID (2) and correlation ID (4). Strings are UTF-8 with signed four-byte lengths; integers are big-endian.

| ID | Command | Successful result |
| ---: | --- | --- |
| 1 | `PRODUCE` | Assigned offset |
| 2 | `FETCH` | Offset/value records |
| 3 | `COMMIT_OFFSET` | Boundary validated, or joined member acknowledgement accepted |
| 4 | `JOIN_GROUP` | Membership registered |
| 5 | `LEAVE_GROUP` | Membership removed |
| 6 | `HEARTBEAT` | Member refreshed |
| 7 | `METADATA` | Topic/partition-count map |
| 8 | `CREATE_TOPIC` | Topic created |
| 9 | `PRODUCE_BATCH` | First assigned offset; records follow in input order |
| 10 | `FETCH_OFFSET` | Registered member's next acknowledged offset as a signed 64-bit integer |
| 11 | `PRODUCE_IDEMPOTENT` | First offset; retry of the latest matching producer sequence returns its original offset |
| 12 | `TRANSACT` | First output offset after atomic single-output-partition append and single-input offset advancement |
| 13 | `JOIN_MANAGED_GROUP` | Generation and assigned partition/next-offset map |
| 14 | `GROUP_ASSIGNMENT` | Current generation and assigned partition/next-offset map |
| 15 | `PRODUCE_CONFIGURED` | First offset for `DURABLE`, or `-1` after queue admission for `ACCEPTED` |

Responses repeat command and correlation ID, then carry a 16-bit error code and length-prefixed error message. Success adds the command's result; failures omit it. See [ErrorCode.java](src/main/java/io/setu/core/protocol/ErrorCode.java) for the error IDs and [ProtocolCodec.java](src/main/java/io/setu/core/protocol/ProtocolCodec.java) for field order.

Decoding validates magic, message kind, command, correlation ID, strict UTF-8, lengths, required fields and trailing bytes. String fields are capped at 4 KiB, batch payloads at 14 MiB / 10,000 records, and fetch payload budgets at 15 MiB. Transport and broker limits can be lower. Grouped fetches require both topic `CONSUME` and group `CONSUMER_GROUP_WRITE` permission on secure servers; ordinary fetches require only topic permission. Member, long-poll and generation fields remain optional where applicable.

### Request trace context

Every request includes two length-prefixed printable ASCII strings immediately after the 11-byte header: W3C `traceparent`, then `tracestate`. Each is limited to **512 bytes**, validated before string allocation. When no trace context exists, both fields are still present with zero lengths, making the minimum request body 19 bytes. Nonempty `tracestate` requires `traceparent`. Command fields follow these mandatory trace fields, including any applicable member, wait or generation fields. No trailing extension data is accepted. Responses carry command/correlation, error and result fields without request trace strings.

Propagation is always enabled in the Java clients over blocking, selector and TLS/mTLS transports. There is no propagation switch, alternate wire format or downgrade/replay path. The trace strings consume existing frame/admission limits and secure request-byte quotas. Recording/export still requires an application SDK or Java agent; a client without one sends the same required fields with empty values when it has no valid parent.

Core carries bounded metadata in `BrokerRequest.traceContext()` / `withTraceContext(TraceContext)` without an OpenTelemetry dependency. W3C semantic validation belongs to client/server instrumentation: bounded but invalid trace parents start a fresh server trace, and invalid vendor state is discarded by the propagator. Trace metadata is not authentication or authorization data. It is excluded from producer fingerprints, transaction journal entries and consumer-state journal records. It is not stored with record payloads or returned by fetches. See [trace propagation](../docs/observability.md#logs-and-request-traces).

Public byte-array APIs copy payloads. Internal `MessageValue.Frame` overloads share immutable slices between protocol and storage, with admission before materialization. Retaining a slice can retain its backing frame.

## Limits and tests

`FETCH_OFFSET` uses the `COMMIT_OFFSET` field layout with a required member and a nonnegative placeholder offset (the Java client sends zero). Secure servers require group `CONSUMER_GROUP_WRITE`.

Batch commands start with `PRODUCE_BATCH`'s topic/partition/count/values layout. Command 15 appends one byte (`0=DURABLE`, `1=ACCEPTED`). Commands 11/12 append a producer string, signed 64-bit epoch and sequence. Command 12 then appends input group/member/topic strings, 32-bit partition, 64-bit next offset and generation (`-1` for explicit membership). Managed commands use the join request layout; success returns a 64-bit generation, 32-bit count and `(32-bit partition, 64-bit next offset)` entries. Managed fetch adds generation after the wait field (send wait zero if needed); member commit/offset lookup can append generation after member. `STALE_GENERATION=13`, `PRODUCER_FENCED=14` and `OUT_OF_ORDER_SEQUENCE=15` are error IDs. See [transaction contracts, bounds, recovery and permissions](../docs/transactions.md).

Replication, failover and multi-output-partition transactions are not implemented. FILE journals support automatic or explicit `compactJournal()` checkpoints; `configureJournalCompaction(bytes)` controls the automatic trigger (64 MiB by default, zero disables it). Storage migration and persistence boundaries are documented in the [storage guide](../setu-storage/README.md).

From the repository root with Java 26:

```powershell
.\mvnw.cmd -pl setu-core -am test
```

Tests cover protocol validation/ownership, offsets, membership, long polling, memory reclamation, expiry and Chronicle recovery. See the [architecture guide](../docs/architecture.md) for module boundaries.

`BrokerJournal` owns generation publication, storage and the metadata lock; `BrokerCheckpoint` encodes committed metadata; `BrokerState` contains state types. `DefaultBroker` retains transaction visibility and lock ordering. Aggregate operational gauges expose readiness, recovery, journal use, writer pressure, consumer lag and gate wait without transport dependencies. The server exports these through its optional operations endpoint.
