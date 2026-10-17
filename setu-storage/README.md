# setu-storage

`setu-storage` owns append-only partition logs, logical offsets and physical storage. `StorageMode.FILE` uses **OpenHFT Chronicle Queue 2026.6** by default. `IN_MEMORY` provides volatile storage behind the same `MessageLog` interface.

## API and runtime

```java
import io.setu.storage.log.MessageLogs;
import io.setu.storage.log.StorageMode;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

try (var log = MessageLogs.open(StorageMode.FILE, Path.of("data/orders/partition-0"), 1024 * 1024)) {
    long offset = log.append("order-1".getBytes(StandardCharsets.UTF_8));
    var records = log.read(offset, 4 * 1024 * 1024);
    System.out.println(records.getFirst().offset());
}
```

Launch with the shared [config/jvm.options](../config/jvm.options). Maven tests, broker launchers and isolated benchmark JVMs use this configuration, including Java 26 module/native access. See [server launch instructions](../setu-server/README.md#standalone-jar) for direct JAR use.

`MessageLog` supports single/batch append, reads by offset, `nextOffset`, `firstOffset` and close. Reads respect payload-byte budgets, never split records and return at most 10,000 records. A zero budget or oversized first record produces an empty page. `ReadAdmission` reserves cumulative materialization/response capacity before copying records.

## Durable storage

`MessageLogs.open(FILE, directory, maxRecordBytes)` wraps `ChronicleMessageLog` with `GroupCommitMessageLog`. [DefaultBroker](../setu-core/README.md#broker-usage) and the standalone server select this path for file mode.

A partition contains daily rolled `.cq4` files, `metadata.cq4t` and an exclusive `.setu.lock`. Chronicle owns native mappings and physical indexes. Versioned Setu documents contain the first logical offset, count and length-prefixed records. Batches split near 1 MiB; an individual larger record stays whole. Dense Setu offsets continue across roll cycles and restart, independently of Chronicle's encoded indexes. A sparse pair of primitive arrays maps logical document offsets to physical indexes.

`queue.block.bytes` controls mapping blocks, with a 64 MiB minimum/default (`MessageLogs.DEFAULT_QUEUE_BLOCK_BYTES`). It does not limit daily file size or enable retention. All native operations and closure serialize on the adapter; only one broker can own a partition.

## Durability and recovery

The MPSC writer admits up to 32 MiB / 1,024 outstanding requests, including the active flush. Adjacent requests share an append. Groups linger up to 250 microseconds; 256 KiB of accounted bytes triggers an immediate flush. Close drains accepted requests. Interrupted callers still receive their accepted write's durability result.

Acknowledgement follows a JDK file force on every touched queue file and roll metadata, covering native index/header changes. Linux uses `FileChannel.force(true)` (`fsync`) for the first force and changes in file size, then `force(false)` (`fdatasync`) for writes within the same size. Both flush dirty shared mappings; `fdatasync` also persists allocation/size metadata needed to retrieve the data, while avoiding timestamp-only journal commits that can wait for unrelated ordered writes on ext4. Chronicle indexes, headers and `metadata.cq4t` are file contents and remain covered. See the [Linux fsync/fdatasync contract](https://man7.org/linux/man-pages/man2/fsync.2.html), [ext4 journal synchronization](https://github.com/torvalds/linux/blob/master/fs/ext4/fsync.c) and [OpenJDK force implementation](https://github.com/openjdk/jdk/blob/master/src/java.base/unix/native/libnio/ch/UnixFileDispatcherImpl.c). Other platforms call `MemorySegment.force()` through a view scoped to each force, followed by `FileChannel.force(true)`. Chronicle's own sync is disabled because the resolved OpenHFT native Windows `msync` returns success without a flush. This is a local OS force boundary, without replication or protection against faulty devices.

Appends and close serialize across the complete force. The committed offset and document boundary publish together only after forcing succeeds. Reads of the previously committed prefix can proceed during JDK forcing; offset checks do not acquire the I/O monitor. Sequential reads reuse the tailer's current index, while partial reads and rewinds seek as needed.

Recovery validates committed headers, counts, lengths and dense offsets, then rebuilds Setu's sparse logical index. Chronicle's native indexes are persisted. Incomplete native documents do not consume recovered offsets; malformed committed documents fail startup without silently discarding later data.

Append failure poisons subsequent operations until reopen. Unacknowledged committed documents can remain after a failed append, so a failed/lost reply has an uncertain outcome. This does not provide idempotent retries or exactly-once delivery.

## In-memory storage and ownership

`InMemoryMessageLog` publishes complete immutable 1,024-reference chunks with CAS and a persistent AVL index. Append, read, prefix reclamation and close use no shared monitor. `discardBefore` removes acknowledged prefixes without resetting offsets; snapshots remain valid after reclamation or closure. The broker can also enable lossy timed expiry.

`MemoryLogBudget` reserves complete batches before publication. Rejection leaves records and offsets unchanged. Standalone memory logs are unlimited unless a budget is supplied; `DefaultBroker` shares a 256 MiB budget by default, and zero disables it. Accounting covers payload backing arrays, wrappers and chunks, including empty records. It excludes transport buffers, native mappings, sparse file indexes and snapshots retained after reclamation.

`MessageValue` copies public byte-array inputs/outputs. Internally, protocol and storage share immutable slices of privately owned frames. A backing frame is charged once per contiguous run of sibling slices, separately in each memory chunk it crosses. Partial reclamation keeps the frame charged until the last slice in that chunk is removed. A retained snapshot can therefore keep more memory alive than its selected payload bytes.

## Migrating existing data

Old `.log` partitions fail startup with an offline migration message. Stop the old broker and preserve its data root. After packaging the server, import into a **new, nonexistent directory outside the source root**:

```powershell
java '@config/jvm.options' -cp 'setu-server/target/lib/*' io.setu.storage.log.LegacyLogMigration data data-chronicle 1048576
```

Run from the repository root. The final argument is the original maximum record size in bytes. The importer checks contiguous segment offsets and partition IDs, preserves record order/empty payloads, and never modifies the source. Invalid or incomplete input fails rather than being truncated. A failed import can leave a partial target; retry into another fresh directory. Verify the imported data before changing `data.dir`. Process-local memberships and committed offsets are not migrated.

## Limits and tests

File retention, payload checksums, replicated storage, persisted consumer metadata and configurable acknowledgement modes are not implemented.

From the repository root with Java 26:

```powershell
.\mvnw.cmd -pl setu-storage test
```

Tests cover offsets, admission, immutable snapshots, daily rolls, mapped growth, incomplete/corrupt documents, exclusive ownership, migration, group-commit failure/shutdown and cleanup.
