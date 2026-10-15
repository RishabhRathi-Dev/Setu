# setu-storage

`setu-storage` owns partition logs and logical offsets. **OpenHFT Chronicle Queue 2026.6 is the default durable engine** behind `StorageMode.FILE`; the former custom file backends and optional comparison module have been removed. `IN_MEMORY` remains available for volatile workloads.

## Durable storage

`MessageLogs.open(FILE, partitionDirectory, maxRecordBytes)` wraps `ChronicleMessageLog` with the bounded `GroupCommitMessageLog` writer. The server and `DefaultBroker(dataDirectory, maxRecordBytes)` use this path automatically.

Each partition contains native daily rolled `.cq4` files, `metadata.cq4t`, and an exclusive `.setu.lock`. Chronicle manages memory mapping and physical indexes. Setu documents carry a versioned header, first logical offset, record count, and length-prefixed values. Batches split near 1 MiB per document; one larger record remains whole. Dense zero-based Setu offsets continue across roll cycles and restart, independently of Chronicle's encoded indexes. A sparse pair of primitive arrays maps document offsets to Chronicle indexes, rebuilt and validated at startup.

`queue.block.bytes` controls mapping block size, with a 64 MiB minimum and default (`MessageLogs.DEFAULT_QUEUE_BLOCK_BYTES`). It is not a file rotation or retention limit. The six-argument `DefaultBroker` constructor accepts this value before the consumer deadlines.

All reads, writes, and native resource closure serialize on the adapter. One broker owns a partition at a time. Immutable fetch snapshots remain usable after subsequent writes or closure. Fetches respect payload-byte budgets, stop at 10,000 records, and reserve materialization/response capacity before copying mapped bytes into private frames. A zero-byte budget or an oversized first record returns no records.

## Durability and recovery

The MPSC writer admits at most 32 MiB / 1,024 outstanding requests, including the active flush. Groups linger up to 250 microseconds; a group with at least 256 KiB of accounted bytes flushes immediately. Accepted requests receive contiguous offsets only after the delegate forces all touched queue files and roll metadata. Close drains admitted requests; failure rejects subsequent operations until reopen. An interrupted caller still receives its accepted write's durability result.

The adapter uses JDK mapped-file `MemorySegment.force()` followed by `FileChannel.force(true)`, including native index/header changes. It deliberately disables Chronicle's own sync: the resolved OpenHFT POSIX Windows implementation returns success without performing `msync`. This bridge preserves an explicit OS force boundary; it is not replicated durability or a guarantee against faulty hardware.

Startup scans committed documents, checks format, dense offsets, counts and lengths, and rebuilds the sparse logical index. Incomplete native documents do not consume recovered offsets. Malformed committed documents fail startup without silently discarding later data. Failed appends can leave unacknowledged committed documents, so clients must treat a failed/unknown acknowledgment as an uncertain outcome.

## Java runtime and usage

Java 26 requires the module/native options in the shared [jvm.options](../config/jvm.options). Maven tests, launch scripts, and benchmark child JVMs consume this same configuration. For a direct server launch from the repository root:

```powershell
java '@config/jvm.options' -jar setu-server/target/setu-server-1.0.0-SNAPSHOT.jar --config config/server.properties
```

For direct storage use with those JVM options:

```java
try (MessageLog log = MessageLogs.open(StorageMode.FILE, partitionDirectory, 1024 * 1024)) {
    long offset = log.append(payload);
    List<LogRecord> records = log.read(offset, 4 * 1024 * 1024);
}
```

## Migrating existing data

Old `.log` partitions fail startup with an offline migration message. Stop the old broker and retain its data root. After packaging the server, import into a **new, nonexistent directory outside the source root**:

```powershell
java '@config/jvm.options' -cp 'setu-server/target/lib/*' io.setu.storage.log.LegacyLogMigration data data-chronicle 1048576
```

The importer reads the old format without modifying it, checks contiguous segment offsets and partition IDs, and preserves record order and empty payloads. Set the last argument to the original maximum record size. Invalid or incomplete input fails instead of truncating the source; a failed import can leave a partial target and must be retried into another fresh directory. Verify the imported data before switching `data.dir`. Membership and committed consumer offsets were process-local in the old engine and are not migrated.

## In-memory storage and ownership

`InMemoryMessageLog` publishes complete batches with CAS using immutable 1,024-reference chunks and a persistent AVL index. Append, fetch, prefix reclamation and close use no shared monitor. `discardBefore` reclaims acknowledged prefixes without resetting offsets; existing snapshots remain valid.

`MemoryLogBudget` reserves whole batches before publication. Capacity rejection leaves records and offsets unchanged. `DefaultBroker` shares a 256 MiB default budget across memory partitions; zero disables it. Accounting includes backing payload arrays, wrappers and chunks, including empty records. It excludes protocol buffers, native mappings, sparse file indexes, and snapshots retained after reclamation; it is not a total heap cap.

`MessageValue` copies public byte-array inputs and outputs. Internal storage and protocol values share immutable slices of privately owned frames. Admission charges a backing frame once per contiguous run of sibling slices, and memory logs charge it in each chunk it crosses. Partially reclaimed frames stay charged until their final slice in that chunk is removed.

## Limits and tests

File retention, payload checksums, replicated storage, persisted consumer metadata and configurable acknowledgment modes remain unimplemented. Native Chronicle indexes are persisted; Setu's sparse logical-offset index is rebuilt at startup.

Run `./mvnw.cmd -pl setu-storage test` from the repository root. Tests cover logical offsets, byte admission, immutable snapshots, daily rolls, mapped block growth, incomplete/corrupt native documents, exclusive ownership, group-commit failure/shutdown, migration, and resource cleanup.
