# setu-storage

`setu-storage` provides the append-only log abstraction used by `setu-core`, with in-memory and segmented file-backed implementations for one partition. It owns record offsets and physical file positions; the broker works with logical offsets and does not interpret the on-disk layout. Project-wide implementation status is summarized in the [parent README](../README.md#current-implementation-snapshot).

## Implemented

- `MessageLog` exposes append, sequential read from an offset, next-offset lookup, and close operations.
- `LogRecord` carries a zero-based offset and an immutable `MessageValue`. Byte-array constructors copy caller input, and `value()` returns a defensive copy. Storage snapshots, segment-offset translation, and protocol records share the immutable payload without cloning it at each boundary.
- `FileMessageLog` stores records in one file, maintains an in-memory primitive `long[]` position index, and serializes operations with synchronized methods. Record lengths come from adjacent positions, avoiding a heap object per indexed record.
- `SegmentedFileMessageLog` rotates a partition across length-prefixed files named by their first logical offset, keeping reads and offsets continuous across files. Fetches locate their starting segment with binary search.
- `InMemoryMessageLog` stores defensive copies in chunks of 1,024 record references and implements the same offset and read-budget behavior.
- `MemoryLogBudget` can be shared across memory logs. Whole-batch reservations atomically check copied-payload and chunk capacity before append; capacity rejection raises `LogCapacityExceededException` without changing offsets or stored records. Partial reclamation releases payload accounting, complete reclamation also releases chunk accounting, and close releases the log's remaining allocation. Empty payloads still consume capacity.
- In-memory logs retain their original logical offsets while removing acknowledged prefixes with `discardBefore`; `firstOffset` reports the retained boundary. Reclamation releases complete chunks and clears references in partially consumed chunks without shifting surviving records.
- `MessageLogs` opens a log for `StorageMode.IN_MEMORY` or `StorageMode.FILE`.
- Each record is encoded as a four-byte signed length followed by the payload bytes.
- Single-record and batch appends synchronize with `FileChannel.force(true)` before returning offsets. A batch write performs one sync per `FileMessageLog` call; if a write reports an I/O failure, that file is truncated back to its prior size.
- Startup recovery scans valid records, rebuilds the in-memory index, and truncates an invalid or partial tail to the end of the last valid record.
- Reads enforce a payload-byte limit without splitting records and cap one call at 10,000 returned records. File reads coalesce adjacent records into windows of up to 1 MiB; a single larger record uses its own larger window.
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

Each segment has an in-memory position index, rebuilt by scanning at startup. Rotation defaults to 1 GiB and can be configured through `DefaultBroker`; a record larger than the configured segment limit is allowed in its own segment. Segments currently have no checksum, persisted index, retention, or format version.

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

Standalone memory logs retain their unlimited default for compatibility. Pass a shared `new MemoryLogBudget(maxBytes)` as the second constructor argument to bound them; zero disables the limit. `DefaultBroker` supplies a shared 256 MiB default budget. Accounting uses conservative payload-array, immutable-wrapper, and reference-chunk allowances, not a measurement of the entire JVM heap. Snapshots can keep payloads alive after log reclamation; these references and protocol buffers are outside this retained-log budget.

`maxRecordBytes` is enforced by both implementations. A zero-byte read budget returns no records. If the first record is larger than the read budget, `read` returns an empty list; the caller must use a larger limit. The budget counts payload bytes and excludes per-record length headers. The broker's default file segment limit is `MessageLogs.DEFAULT_MAX_SEGMENT_BYTES` (1 GiB); its six-argument constructor accepts a custom segment limit.

## Durability and recovery boundaries

The append path calls `force(true)` before it updates the in-memory index and reports success. Single-record writes sync each record; `appendBatch` syncs the entire encoded batch once. Segmented batches that cross a rotation boundary sync once per touched segment. This provides a clear durable-before-ack boundary at the Java file-channel level, but is not a claim of replicated durability or protection from every device/filesystem failure. No configurable acknowledgement modes or cross-request group commit are provided.

Recovery treats the first invalid length or incomplete final record as a tail and truncates from that point. There is no checksum to distinguish a corrupt middle record from an invalid tail; an invalid record may therefore cause later bytes in the file to be discarded. Operators should back up data before manual repair.

## Not implemented

- Retention, checksums, format versioning, or a persisted offset index.
- FFM/`MemorySegment` mapping, off-heap buffers, read caching, asynchronous I/O, or cross-request group commit. `FileMessageLog` syncs once per explicit batch; segmented writes sync once per touched segment batch.
- Backpressure, bounded write queues, storage metrics, and recovery diagnostics beyond thrown I/O errors.
- Multi-process file locking, replicated storage, snapshots, and control-plane metadata persistence.

## Build and tests

From the repository root with the project-required Java 25 JDK:

```powershell
.\mvnw.cmd -pl setu-storage test
```

`setu-core` consumes this module through `MessageLog`; storage does not depend on broker command or protocol types.
