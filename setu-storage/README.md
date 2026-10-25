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

`queue.block.bytes` controls mapping blocks, with a 64 MiB minimum/default (`MessageLogs.DEFAULT_QUEUE_BLOCK_BYTES`). It does not limit daily file size. Optional file retention is configured separately. All native operations and closure serialize on the adapter; only one broker can own a partition.

`file.retention.millis` defaults to zero (disabled). A positive value expires whole files once their daily cycle end is old enough, independently of consumer acknowledgements. The broker sweeps at startup and every minute; the newest populated cycle always remains, even while idle. Standalone `MessageLog` callers can invoke `discardExpiredFiles(retentionMillis)` themselves. Cleanup checkpoints a checksummed logical/physical boundary in `.setu-retention`, releases all queue mappings and force channels, removes obsolete files, and reopens the queue under the partition lock. Reads and appends pause during this operation. Logical offsets never reset; storage reads clamp to `firstOffset`, while broker fetches below it return `OFFSET_OUT_OF_RANGE` with a resume offset. Keep the checkpoint with the queue files when backing up or restoring a partition.

### Standalone retention example

When using storage directly, the caller schedules cleanup. This example opens an existing partition, performs one seven-day sweep, then reads from the retained boundary:

```java
import io.setu.storage.log.MessageLogs;
import io.setu.storage.log.StorageMode;
import java.nio.file.Path;
import java.time.Duration;

try (var log = MessageLogs.open(StorageMode.FILE,
        Path.of("data/orders/partition-0"), 1024 * 1024)) {
    log.discardExpiredFiles(Duration.ofDays(7).toMillis());
    long firstRetained = log.firstOffset();
    long nextAppend = log.nextOffset();
    var page = log.read(firstRetained, 4 * 1024 * 1024);
    System.out.printf("Retained range [%d, %d), page records=%d%n",
            firstRetained, nextAppend, page.size());
}
```

Stop any broker that owns the partition before running this example. A newly created partition has no history to expire; one populated cycle is always kept. Storage reads below `firstOffset()` clamp to that boundary. Applications that need explicit gap detection must compare their requested offset with the boundary or use the broker's `OFFSET_OUT_OF_RANGE` behavior. Use the [embedded broker example](../setu-core/README.md#file-retention-example) for automatic background maintenance.

## Durability and recovery

The MPSC writer admits up to 32 MiB / 1,024 outstanding requests, including the active flush. Adjacent requests share an append. Groups linger up to 250 microseconds; 256 KiB of accounted bytes triggers an immediate flush. Close drains accepted requests. Interrupted callers still receive their accepted write's durability result.

Acknowledgement follows a JDK file force on every touched queue file and roll metadata, covering native index/header changes. Linux uses `FileChannel.force(true)` (`fsync`) for the first force and changes in file size, then `force(false)` (`fdatasync`) for writes within the same size. Both flush dirty shared mappings; `fdatasync` also persists allocation/size metadata needed to retrieve the data, while avoiding timestamp-only journal commits that can wait for unrelated ordered writes on ext4. Chronicle indexes, headers and `metadata.cq4t` are file contents and remain covered. See the [Linux fsync/fdatasync contract](https://man7.org/linux/man-pages/man2/fsync.2.html), [ext4 journal synchronization](https://github.com/torvalds/linux/blob/master/fs/ext4/fsync.c) and [OpenJDK force implementation](https://github.com/openjdk/jdk/blob/master/src/java.base/unix/native/libnio/ch/UnixFileDispatcherImpl.c). Other platforms call `MemorySegment.force()` through a view scoped to each force, followed by `FileChannel.force(true)`. Chronicle's own sync is disabled because the resolved OpenHFT native Windows `msync` returns success without a flush. This is a local OS force boundary, without replication or protection against faulty devices.

Appends and close serialize across the complete force. The committed offset and document boundary publish together only after forcing succeeds. Reads of the previously committed prefix can proceed during JDK forcing; offset checks do not acquire the I/O monitor. Sequential reads reuse the tailer's current index, while partial reads and rewinds seek as needed.

Recovery validates committed headers, counts, lengths and dense offsets, then rebuilds Setu's sparse logical index starting at the persisted retention boundary when present. Chronicle's native indexes are persisted. Incomplete native documents do not consume recovered offsets; malformed committed documents fail startup without silently discarding later data.

Append failure poisons subsequent operations until reopen. Unacknowledged committed documents can remain after a failed append, so a failed/lost reply has an uncertain outcome. This does not provide idempotent retries or exactly-once delivery.

## In-memory storage and ownership

`InMemoryMessageLog` publishes complete immutable 1,024-reference chunks with CAS and a persistent AVL index. Append, read, prefix reclamation and close use no shared monitor. `discardBefore` removes acknowledged prefixes without resetting offsets; snapshots remain valid after reclamation or closure. The broker can also enable lossy timed expiry.

`MemoryLogBudget` reserves complete batches before publication. Rejection leaves records and offsets unchanged. Standalone memory logs are unlimited unless a budget is supplied; `DefaultBroker` shares a 256 MiB budget by default, and zero disables it. Accounting covers payload backing arrays, wrappers and chunks, including empty records. It excludes transport buffers, native mappings, sparse file indexes and snapshots retained after reclamation.

`MessageValue` copies public byte-array inputs/outputs. Internally, protocol and storage share immutable slices of privately owned frames. A backing frame is charged once per contiguous run of sibling slices, separately in each memory chunk it crosses. Partial reclamation keeps the frame charged until the last slice in that chunk is removed. A retained snapshot can therefore keep more memory alive than its selected payload bytes.

## Migrating existing data

Old `.log` partitions fail startup with an offline migration message. Stop the old broker and preserve its data root. After packaging the server, import into a **new, nonexistent directory outside the original data directory**:

```powershell
java '@config/jvm.options' -cp 'setu-server/target/lib/*' io.setu.storage.log.LegacyLogMigration data data-chronicle 1048576
```

Run from the repository root. The final argument is the original maximum record size in bytes. The importer checks contiguous segment offsets and partition IDs, preserves record order/empty payloads, and never modifies the source. Invalid or incomplete input fails rather than being truncated. A failed import can leave a partial target; retry into another fresh directory. Verify the imported data before changing `data.dir`. Process-local memberships and committed offsets are not migrated.

## Limits and tests

New Setu documents use format 2: the existing format/offset/count header followed by a native-endian 32-bit CRC32C of all encoded record lengths and payloads. Recovery and fetch validate this checksum before exposing records. Checksum scans reuse an 8 KiB scratch buffer; writes checksum immutable payload arrays directly. Format 1 documents remain readable without checksum verification, and appending does not rewrite them. Older Setu binaries cannot read format 2; back up before upgrading and do not downgrade a data directory after new writes. CRC32C detects accidental corruption and is not authentication or replication. Core also uses this log implementation for its separate consumer journal.

`MessageLog.appendValuesAsync` exposes completion separately from admission; the group-commit implementation admits a complete immutable batch to the existing bounded queue and completes its future only after forcing. `awaitPendingWrites` drains admitted work when callers exclude new admissions. Broker `ACCEPTED` mode can reply after admission, before that future completes; ordinary appends retain their existing force boundary. Clean close still drains accepted writes. Replicated storage is not implemented. See [broker acknowledgement and transaction semantics](../docs/transactions.md).

From the repository root with Java 26:

```powershell
.\mvnw.cmd -pl setu-storage test
```

Tests cover offsets, admission, immutable snapshots, daily rolls, mapped growth, incomplete/corrupt documents, exclusive ownership, migration, group-commit failure/shutdown and cleanup.

CI also runs a standalone Linux force diagnostic without changing the throughput gates. To run it from the repository root:

```bash
probe_root=$(mktemp -d)
mkdir -p "$probe_root/classes"
javac -d "$probe_root/classes" \
  setu-storage/src/main/java/io/setu/storage/log/ChronicleFileSync.java \
  scripts/diagnostics/FileForceProbe.java
java --enable-native-access=ALL-UNNAMED -cp "$probe_root/classes" \
  io.setu.storage.log.FileForceProbe "$probe_root"
```

It retains a JFR recording, checks metadata barrier selection and mapped growth/header rewrites through a separate descriptor, and models the scheduling backlog from one 383 ms force delay. The delayed sample and recovery samples remain in the percentiles; this diagnostic is independent of the strict FILE baseline.

## Operational counters

`GroupCommitMessageLog.flushNanos()` and `flushCount()` report cumulative full delegate append/force time and attempts, including failed attempts. They exclude queue waiting and are not isolated `FileChannel.force` timing. Outstanding byte/request gauges continue to include the active flush. The core-owned consumer journal uses versioned checkpoints and generation reclamation; see [journal checkpoints](../docs/journal-checkpoints.md).
