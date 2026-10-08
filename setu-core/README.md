# setu-core

`setu-core` contains the broker API and the first single-process broker implementation, plus the protocol v1 request, response, and codec types shared by the server and client. It depends on `setu-storage` for the per-partition append-only log. The intended module boundaries and overall status are summarized in the [parent README](../README.md#current-implementation-snapshot).

## Implemented

- `Broker` and `BrokerException` define the broker execution/close boundary and structured broker failures.
- `DefaultBroker` implements topic creation, metadata, produce, fetch, offset commits, and basic consumer-group join/leave/heartbeat.
- File-backed topic and partition logs are created under the configured data directory and recovered at startup; memory-backed topics are process-local.
- `Command`, `BrokerRequest`, `BrokerResponse`, `ProtocolRecord`, `ErrorCode`, and `ProtocolException` define protocol v1 values.
- `ProtocolCodec` encodes and validates request/response bodies. `FrameCodec` provides the shared length-prefixed TCP frame format used by client and server.
- Unit tests cover protocol encoding/validation and the broker operations/recovery paths.

## Broker usage

```java
import io.setu.core.broker.Broker;
import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.Command;

try (Broker broker = new DefaultBroker(dataDirectory, 1024 * 1024)) {
    BrokerResponse created = broker.execute(new BrokerRequest(
            1, Command.CREATE_TOPIC, "orders", -1, -1, -1,
            null, 4, null, null));
    BrokerResponse appended = broker.execute(new BrokerRequest(
            2, Command.PRODUCE, "orders", 0, -1, -1,
            "order-1".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, null, null));
}
```

The constructor above uses durable local file logs. For ephemeral in-process logs, use `new DefaultBroker(1024 * 1024)`. To select explicitly, use `new DefaultBroker(StorageMode.IN_MEMORY, null, maxRecordBytes)` or `new DefaultBroker(StorageMode.FILE, dataDirectory, maxRecordBytes)` (import `io.setu.storage.log.StorageMode`). In-memory brokers do not create or scan a data directory; their topics and records are lost when the broker closes.

Applications that expose a network listener should use `setu-server` to handle transport, protocol framing, security checks, and dispatch. A direct call to `Broker.execute` bypasses those network security gates.

## Protocol v1 commands

| Command | Request fields | Successful response data |
| --- | --- | --- |
| `PRODUCE` | topic, partition, value bytes | appended offset |
| `FETCH` | topic, partition, starting offset, maximum payload bytes, optional group/member | records with offsets and byte values |
| `COMMIT_OFFSET` | consumer group, topic, partition, next offset, optional member acknowledgement | none |
| `JOIN_GROUP` | group, topic, member | none |
| `LEAVE_GROUP` | group, member | none |
| `HEARTBEAT` | group, member | none |
| `METADATA` | none | topic to partition-count map |
| `CREATE_TOPIC` | topic, partition count | none |

The binary body starts with a 12-byte header: the `SETU` magic integer, version byte, message-kind byte, command ID, and correlation ID. Strings and byte arrays are length-prefixed. TCP framing adds a separate four-byte body length. The server README documents the field encoding and the request/error flow in more detail.

The codec validates magic, version, message kind, known command, correlation ID, UTF-8, field lengths, command-specific required fields, and trailing bytes. Request strings are limited to 4 KiB, record payloads to 16 MiB at codec level, and fetch requests to 15 MiB. The broker default may impose a smaller configured record maximum.

## Current broker and persistence semantics

- Topic names use ASCII letters, digits, `.`, `_`, and `-`; `.` and `..` are rejected. Topic length is limited to 249 characters.
- A topic may have 1–1,024 partitions. The broker currently limits the process to 10,000 topics.
- Each partition has a zero-based, sequential offset namespace backed by one `MessageLog`.
- In file mode, a successful produce is returned after `FileMessageLog` appends and synchronizes the record with `FileChannel.force(true)`. In memory mode, records are retained only for the broker lifetime.
- Fetch uses an offset and byte limit. An oversized first record for the requested byte limit yields an empty batch; records are not split.
- A joined group member can fetch with its group/member identity and acknowledge the next offset through `COMMIT_OFFSET`. In memory mode, a new member starts at the current partition end and records are reclaimed only through the lowest acknowledged offset across registered members for that topic. Leaving removes that member from the retention floor. File mode keeps the full log and permits reads from offset zero.
- Members that leave fetched records unacknowledged past the configured acknowledgement deadline are removed from the group. Members with no fetch, acknowledgement, or heartbeat activity past the inactivity deadline are also removed. Defaults are 30 seconds for acknowledgement and 60 seconds for inactivity; the explicit `DefaultBroker` constructor can override both. Expiry is checked on broker requests, including produces, so stale members cannot hold memory records during active traffic.
- Committed offsets and consumer group members are held in memory. The current group methods provide simple membership bookkeeping, not coordinator semantics.
- On restart, topics/partition directories and message records are discovered from the data directory; group membership and committed offsets are not restored.

## Not implemented

- Full producer/consumer objects, partition routing, partition assignment, consumer coordination, rebalancing, and delivery guarantees.
- Durable control-plane metadata, PostgreSQL integration, topic configuration, replication, or multi-broker coordination.
- Storage segment rotation, retention, checksums, a persisted index, FFM/`MemorySegment`, or configurable acknowledgement modes.
- Protocol negotiation beyond version 1, paging/cursors, async dispatch contracts, and schema evolution tooling.

## Build and tests

From the repository root with the project-required Java 25 JDK:

```powershell
.\mvnw.cmd -pl setu-core -am test
```

The Maven reactor also builds `setu-storage`. The root README is the source of truth for project-wide goals versus shipped scope.
