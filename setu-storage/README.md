# setu-storage

`setu-storage` currently provides the append-only log abstraction used by `setu-core` and a file-backed implementation for one partition. It owns record offsets and physical file positions; the broker works with logical offsets and does not interpret the on-disk layout. Project-wide implementation status is summarized in the [parent README](../README.md#current-implementation-snapshot).

## Implemented

- `MessageLog` exposes append, sequential read from an offset, next-offset lookup, and close operations.
- `LogRecord` carries a zero-based offset and defensively copies its byte value.
- `FileMessageLog` stores records in one file, maintains an in-memory offset-to-position list, and serializes operations with synchronized methods.
- Each record is encoded as a four-byte signed length followed by the payload bytes.
- Appends synchronize the file with `FileChannel.force(true)` before returning an offset. If an append operation reports an I/O failure, it attempts to truncate back to the prior file size.
- Startup recovery scans valid records, rebuilds the in-memory index, and truncates an invalid or partial tail to the end of the last valid record.
- Reads enforce a byte limit without splitting records and cap one call at 10,000 returned records.
- Tests cover append/read/offset behavior and cleanup of a partial tail during recovery.

## On-disk layout

`DefaultBroker` creates the following layout (the storage module itself accepts a file path):

```text
<data-directory>/
└── <topic>/
    └── partition-<partition-id>/
        └── 000000000000.log
```

Offsets are reconstructed from record order at startup. The record file has no checksum or persisted index. The implementation currently supports one file per partition and does not rotate it.

## API outline

```java
try (MessageLog log = new FileMessageLog(path, 1024 * 1024)) {
    long offset = log.append(payload);
    List<LogRecord> batch = log.read(offset, 4 * 1024 * 1024);
    long nextOffset = log.nextOffset();
}
```

`maxRecordBytes` is enforced by `FileMessageLog`. A zero-byte read budget returns no records. If the first record is larger than the read budget, `read` returns an empty list; the caller must use a larger limit. The budget counts payload bytes and excludes per-record length headers.

## Durability and recovery boundaries

The append path calls `force(true)` before it updates the in-memory index and reports success. This provides a clear per-append synchronization boundary at the Java file-channel level, with potential throughput cost. It is not a claim of replicated durability or protection from every device/filesystem failure. No configurable acknowledgement modes or group commit are provided.

Recovery treats the first invalid length or incomplete final record as a tail and truncates from that point. There is no checksum to distinguish a corrupt middle record from an invalid tail; an invalid record may therefore cause later bytes in the file to be discarded. Operators should back up data before manual repair.

## Not implemented

- Segment files, rotation, retention, compaction, checksums, format versioning, or a persisted offset index.
- FFM/`MemorySegment` mapping, off-heap buffers, read caching, asynchronous I/O, or batched/group commit.
- Backpressure, bounded write queues, storage metrics, and recovery diagnostics beyond thrown I/O errors.
- Multi-process file locking, replicated storage, snapshots, and control-plane metadata persistence.

## Build and tests

From the repository root with the project-required Java 25 JDK:

```powershell
.\mvnw.cmd -pl setu-storage test
```

`setu-core` consumes this module through `MessageLog`; storage does not depend on broker command or protocol types.
