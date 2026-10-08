# setu-storage

`setu-storage` provides the append-only log abstraction used by `setu-core`, with in-memory and segmented file-backed implementations for one partition. It owns record offsets and physical file positions; the broker works with logical offsets and does not interpret the on-disk layout. Project-wide implementation status is summarized in the [parent README](../README.md#current-implementation-snapshot).

## Implemented

- `MessageLog` exposes append, sequential read from an offset, next-offset lookup, and close operations.
- `LogRecord` carries a zero-based offset and defensively copies its byte value.
- `FileMessageLog` stores records in one file, maintains an in-memory offset-to-position list, and serializes operations with synchronized methods.
- `SegmentedFileMessageLog` rotates a partition across length-prefixed files named by their first logical offset, keeping reads and offsets continuous across files.
- `InMemoryMessageLog` stores defensive copies of records in process memory and implements the same offset and read-budget behavior.
- In-memory logs retain their original logical offsets while removing acknowledged prefixes with `discardBefore`; `firstOffset` reports the retained boundary.
- `MessageLogs` opens a log for `StorageMode.IN_MEMORY` or `StorageMode.FILE`.
- Each record is encoded as a four-byte signed length followed by the payload bytes.
- Appends synchronize the file with `FileChannel.force(true)` before returning an offset. If an append operation reports an I/O failure, it attempts to truncate back to the prior file size.
- Startup recovery scans valid records, rebuilds the in-memory index, and truncates an invalid or partial tail to the end of the last valid record.
- Reads enforce a byte limit without splitting records and cap one call at 10,000 returned records.
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

`maxRecordBytes` is enforced by both implementations. A zero-byte read budget returns no records. If the first record is larger than the read budget, `read` returns an empty list; the caller must use a larger limit. The budget counts payload bytes and excludes per-record length headers. The broker's default file segment limit is `MessageLogs.DEFAULT_MAX_SEGMENT_BYTES` (1 GiB); its six-argument constructor accepts a custom segment limit.

## Durability and recovery boundaries

The append path calls `force(true)` before it updates the in-memory index and reports success. This provides a clear per-append synchronization boundary at the Java file-channel level, with potential throughput cost. It is not a claim of replicated durability or protection from every device/filesystem failure. No configurable acknowledgement modes or group commit are provided.

Recovery treats the first invalid length or incomplete final record as a tail and truncates from that point. There is no checksum to distinguish a corrupt middle record from an invalid tail; an invalid record may therefore cause later bytes in the file to be discarded. Operators should back up data before manual repair.

## Not implemented

- Retention, checksums, format versioning, or a persisted offset index.
- FFM/`MemorySegment` mapping, off-heap buffers, read caching, asynchronous I/O, or batched/group commit.
- Backpressure, bounded write queues, storage metrics, and recovery diagnostics beyond thrown I/O errors.
- Multi-process file locking, replicated storage, snapshots, and control-plane metadata persistence.

## Build and tests

From the repository root with the project-required Java 25 JDK:

```powershell
.\mvnw.cmd -pl setu-storage test
```

`setu-core` consumes this module through `MessageLog`; storage does not depend on broker command or protocol types.
