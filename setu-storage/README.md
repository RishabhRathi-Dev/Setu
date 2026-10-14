# setu-storage

`setu-storage` provides the append-only log abstraction used by `setu-core`, with in-memory and segmented file-backed implementations for one partition. It owns record offsets and physical file positions; the broker works with logical offsets and does not interpret the on-disk layout. Project-wide implementation status is summarized in the [parent README](../README.md#current-implementation-snapshot).

## Implemented

- `MessageLog` exposes append, sequential read from an offset, next-offset lookup, and close operations.
- `LogRecord` carries a zero-based offset and an immutable `MessageValue`. Byte-array constructors copy caller input, and `value()` returns a defensive copy. Storage snapshots, segment-offset translation, and protocol records share the immutable payload without cloning it at each boundary.
- `FileMessageLog` stores records in one file and serializes operations with synchronized methods. Equal-sized records use arithmetic positions, so their index stays constant in size as the file grows. When sizes vary, the log materializes a primitive `long[]` position index, including prior uniform records. Record lengths come from adjacent positions, avoiding a heap object per indexed record.
- `SegmentedFileMessageLog` rotates a partition across length-prefixed files named by their first logical offset, keeping reads and offsets continuous across files. Fetches locate their starting segment with binary search.
- `InMemoryMessageLog` publishes complete batches with compare-and-set, using immutable chunks of 1,024 record references and a persistent AVL index. Append, read, prefix reclamation, timed retention, and close use no shared monitor. Reads capture one consistent snapshot; concurrent appends cannot expose partial batches or gaps in published offsets.
- `MemoryLogBudget` can be shared across memory logs. Whole-batch reservations atomically check copied-payload and chunk capacity before append; capacity rejection raises `LogCapacityExceededException` without changing offsets or stored records. Partial reclamation releases payload accounting, complete reclamation also releases chunk accounting, and close releases the log's remaining allocation. Empty payloads still consume capacity.
- In-memory logs retain their original logical offsets while removing acknowledged prefixes with `discardBefore`; `firstOffset` reports the retained boundary. Reclamation drops complete chunks and copies only the partially retained chunk. Existing fetch snapshots remain valid after reclamation or close.
- `MessageLogs` opens a log for `StorageMode.IN_MEMORY` or `StorageMode.FILE`.
- Each record is encoded as a four-byte signed length followed by the payload bytes.
- Single-record and batch appends synchronize with `FileChannel.force(true)` before returning offsets. A batch write performs one sync per `FileMessageLog` call; if a write reports an I/O failure, that file is truncated back to its prior size.
- `GroupCommitMessageLog` provides a bounded multiple-producer, single-consumer queue. One CAS atomically admits and publishes a request together with its byte/request reservation. The writer detaches producer stacks and reverses them into FIFO groups, sharing durable appends across adjacent requests. Active flushes count toward both limits. Failure rejects new requests and fails all accepted writes; close drains accepted writes before closing the delegate. Interrupted callers still receive their durability result and have their interrupt status restored.
- Startup recovery scans valid records, rebuilds uniform-position metadata or the variable-length index, and truncates an invalid or partial tail to the end of the last valid record.
- Reads enforce a payload-byte limit without splitting records and cap one call at 10,000 returned records. File reads coalesce adjacent records into windows of up to 1 MiB; a single larger record uses its own larger window.
- File records share immutable slices of their read window; keeping one record retains that window. Public byte-array access still makes defensive copies. Memory fetches reserve response capacity once per immutable chunk before constructing result records. Memory-log and group-commit admission conservatively charge each slice for its full backing array when accepting externally supplied `MessageValue` instances.
- Tests cover append/read/offset behavior, segment rotation and recovery, and cleanup of a partial tail.

## On-disk layout

`DefaultBroker` stores partition segments under this layout. Segment filenames are zero-padded base offsets; the first segment remains `000000000000.log`, so existing single-file partitions are recovered as the first segment.

```text
<data-directory>/
└── <topic>/
    └── partition-<partition-id>/
        ├── 000000000000.log
        ├── 000000000100.log
        └── 000000000200.log
```

Each segment has in-memory position metadata, rebuilt by scanning at startup. Uniform records require only their count and encoded record size; variable-length segments use a per-record position index. Rotation defaults to 1 GiB and can be configured through `DefaultBroker`; a record larger than the configured segment limit is allowed in its own segment. Segments currently have no checksum, persisted index, retention, or format version.

## API outline

```java
try (MessageLog log = new SegmentedFileMessageLog(partitionDirectory, 1024 * 1024,
        1024L * 1024 * 1024)) {
    long offset = log.append(payload);
    List<LogRecord> batch = log.read(offset, 4 * 1024 * 1024);
    long nextOffset = log.nextOffset();
}
```

For ephemeral storage, use `new InMemoryMessageLog(maxRecordBytes)`. In-memory offsets start at zero and remain monotonic as acknowledged prefixes are reclaimed. `DefaultBroker(maxRecordBytes)` selects this mode; the existing `DefaultBroker(dataDirectory, maxRecordBytes)` constructor continues to select file storage. The three-argument broker constructor accepts an explicit `StorageMode`.

Standalone memory logs retain their unlimited default for compatibility. Pass a shared `new MemoryLogBudget(maxBytes)` as the second constructor argument to bound them; zero disables the limit. `DefaultBroker` supplies a shared 256 MiB default budget. Accounting uses conservative payload-array, immutable-wrapper, and reference-chunk allowances, not a measurement of the entire JVM heap. Snapshots can keep payloads alive after log reclamation; these references, transient copied index paths/chunks, and protocol buffers are outside this retained-log budget. Contending append attempts reserve capacity before building their candidate state and return the reservation on a failed CAS; admission can conservatively reject while other reservations or pending releases occupy the shared budget.

`maxRecordBytes` is enforced by both implementations. A zero-byte read budget returns no records. If the first record is larger than the read budget, `read` returns an empty list; the caller must use a larger limit. The budget counts payload bytes and excludes per-record length headers. The broker's default file segment limit is `MessageLogs.DEFAULT_MAX_SEGMENT_BYTES` (1 GiB); its six-argument constructor accepts a custom segment limit.

## Durability and recovery boundaries

The file append path calls `force(true)` before it updates the in-memory index and reports success. Direct single-record writes sync each record; `appendBatch` syncs the entire encoded batch once. Segmented batches that cross a rotation boundary sync once per touched segment. `MessageLogs.open(FILE, ...)` wraps the segmented log in a group-commit writer with a 32 MiB/1,024-request limit and 250 microsecond linger. Requests in a group receive contiguous offsets only after the shared durable append succeeds. `outstandingBytes()` and `outstandingRequests()` include the active flush. This provides a clear durable-before-ack boundary at the Java file-channel level, but is not a claim of replicated durability or protection from every device/filesystem failure. Configurable acknowledgement modes are not provided.

Lock-free queue publication and memory-log state changes do not make the entire broker lock-free. The file delegate still serializes file/index operations; callers wait for durable completion, and idle writers park. The broker retains locks for membership/acknowledgement bookkeeping, long-poll registration, and file-fetch scheduling, while memory fetch materialization runs outside those locks.

Recovery treats the first invalid length or incomplete final record as a tail and truncates from that point. There is no checksum to distinguish a corrupt middle record from an invalid tail; an invalid record may therefore cause later bytes in the file to be discarded. Operators should back up data before manual repair.

## Not implemented

- Retention, checksums, format versioning, or a persisted offset index.
- FFM/`MemorySegment` mapping, off-heap buffers, read caching, or asynchronous file I/O. `FileMessageLog` syncs once per explicit batch; segmented writes sync once per touched segment batch.
- A storage metrics endpoint and recovery diagnostics beyond thrown I/O errors.
- Multi-process file locking, replicated storage, snapshots, and control-plane metadata persistence.

## Build and tests

From the repository root with the project-required Java 26 JDK:

```powershell
.\mvnw.cmd -pl setu-storage test
```

`setu-core` consumes this module through `MessageLog`; storage does not depend on broker command or protocol types.
