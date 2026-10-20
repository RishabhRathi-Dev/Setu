# setu-core

`setu-core` owns the single-process broker and protocol v1 types shared by client and server. It uses [setu-storage](../setu-storage/README.md) for partition logs; transport and security enforcement belong to [setu-server](../setu-server/README.md).

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
| Membership | Explicit join, leave, heartbeat and next-offset acknowledgement |
| Expiry | Default 30-second acknowledgement / 60-second inactivity deadlines; maintenance runs every 10 ms |
| Restart | File topics and records recover; memberships and committed offsets do not |

Memory members start at the partition end when they join. Acknowledgements reclaim through the lowest registered member's next offset; leaving or expiry removes that member's retention claim. File storage keeps full history by default. Optional `fileRetentionMillis` on the extended constructor expires completed daily files independently of consumer progress; fetches before the retained boundary return `OFFSET_OUT_OF_RANGE` with a resume offset. Consumer state is bookkeeping, without partition assignment or rebalancing.

Memory brokers share a 256 MiB retained-log budget by default. Whole-batch admission failure returns `QUOTA_EXCEEDED` without consuming offsets. Zero disables that budget. Protocol buffers and file indexes are outside it. See [storage ownership and admission](../setu-storage/README.md).

Memory fetches use immutable snapshots outside the membership/long-poll lock. Durable storage serializes native I/O, while membership, acknowledgement and waiter coordination retain locks. CAS publication does not make the entire broker lock-free.

## Protocol v1

`FrameCodec` adds a signed, big-endian four-byte body length. `ProtocolCodec` encodes a 12-byte body header: `SETU` magic (4 bytes), version (1), kind (1), command ID (2) and correlation ID (4). Strings are UTF-8 with signed four-byte lengths; integers are big-endian.

| ID | Command | Successful result |
| ---: | --- | --- |
| 1 | `PRODUCE` | Assigned offset |
| 2 | `FETCH` | Offset/value records |
| 3 | `COMMIT_OFFSET` | Commit or member acknowledgement accepted |
| 4 | `JOIN_GROUP` | Membership registered |
| 5 | `LEAVE_GROUP` | Membership removed |
| 6 | `HEARTBEAT` | Member refreshed |
| 7 | `METADATA` | Topic/partition-count map |
| 8 | `CREATE_TOPIC` | Topic created |
| 9 | `PRODUCE_BATCH` | First assigned offset; records follow in input order |

Responses repeat command and correlation ID, then carry a 16-bit error code and length-prefixed error message. Success adds the command's result; failures omit it. See [ErrorCode.java](src/main/java/io/setu/core/protocol/ErrorCode.java) for the error IDs and [ProtocolCodec.java](src/main/java/io/setu/core/protocol/ProtocolCodec.java) for field order.

Decoding validates version, command, correlation ID, strict UTF-8, lengths, required fields and trailing bytes. String fields are capped at 4 KiB, batch payloads at 14 MiB / 10,000 records, and fetch payload budgets at 15 MiB. Transport and broker limits can be lower. Optional member and long-poll fields extend v1 requests; unsupported versions are rejected rather than negotiated.

Public byte-array APIs copy payloads. Internal `MessageValue.Frame` overloads share immutable slices between protocol and storage, with admission before materialization. Retaining a slice can retain its backing frame.

## Limits and tests

Replication, durable consumer metadata, automatic group coordination and idempotent/exactly-once delivery are not implemented. Storage migration and persistence boundaries are documented in the [storage guide](../setu-storage/README.md).

From the repository root with Java 26:

```powershell
.\mvnw.cmd -pl setu-core -am test
```

Tests cover protocol validation/ownership, offsets, membership, long polling, memory reclamation, expiry and Chronicle recovery. See the [architecture guide](../docs/architecture.md) for module boundaries.
