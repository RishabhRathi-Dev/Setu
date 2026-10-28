# Runnable examples

These Java 26 programs demonstrate individual Setu features. Run commands from the repository root with JDK 26 on `JAVA_HOME` and `PATH`. Each source file runs directly through Java's source-file launcher; no separate examples module is needed.

Build the runtime dependencies first:

```powershell
.\mvnw.cmd -pl setu-server -am package
```

On Linux/macOS, use `bash ./mvnw -pl setu-server -am package`. The commands below work in PowerShell and Bash. Keep the shared `config/jvm.options`: file storage requires its module/native-access options. Rebuild after changing library sources so `setu-server/target/lib/` contains the current code.

FILE examples automatically use the Setu-owned io_uring backend on supported
Linux, with startup fallback when ring setup is unavailable. No additional
backend properties or native library installation are needed.

| Example | Demonstrates | Running broker required? |
| --- | --- | --- |
| [Recovery demo project](recovery-demo/README.md) | Lost transaction reply, retry, checkpoint and forced-process recovery; optional contention diagnostic | No |
| [SetuQuickstart.java](SetuQuickstart.java) | Batch produce, member fetch, acknowledgement and leaving a group | Yes |
| [SetuBatchAndPartitions.java](SetuBatchAndPartitions.java) | Two explicit partitions, independent offsets, paginated fetch and per-partition acknowledgement | Yes |
| [SetuLongPolling.java](SetuLongPolling.java) | Separate producer/consumer connections, arrival during a waiting fetch and an empty timeout response | Yes |
| [SetuEmbeddedBroker.java](SetuEmbeddedBroker.java) | Direct broker calls, joining before produce and memory reclamation after acknowledgement | No |
| [SetuFileRecovery.java](SetuFileRecovery.java) | Durable records and topics across broker close/reopen, then continued offsets | No |
| [SetuFileRetention.java](SetuFileRetention.java) | One offline age-based file sweep, retained range and recovery of that range | No; stop the partition's owner first |
| [SetuMemoryRetention.java](SetuMemoryRetention.java) | Lossy timed expiry of unacknowledged data and `OFFSET_OUT_OF_RANGE` | No |

## Client examples

Start a local plaintext broker in the first terminal:

```powershell
.\scripts\start-broker.ps1 --config config/server.properties
```

On Linux/macOS, use `bash ./scripts/start-broker.sh --config config/server.properties`. Then run any client example in a second terminal:

```powershell
java '@config/jvm.options' -cp 'setu-server/target/lib/*' examples/SetuQuickstart.java
java '@config/jvm.options' -cp 'setu-server/target/lib/*' examples/SetuBatchAndPartitions.java
java '@config/jvm.options' -cp 'setu-server/target/lib/*' examples/SetuLongPolling.java
```

Each accepts optional `host port` arguments, for example:

```powershell
java '@config/jvm.options' -cp 'setu-server/target/lib/*' examples/SetuLongPolling.java 127.0.0.1 9092
```

The client examples work with both `FILE` and `IN_MEMORY` brokers. They join before producing so memory data remains available until acknowledgement. Quickstart reuses its topic; the other two create uniquely named topics on every run. File-mode topics remain on disk after the examples finish.

The batch example prints offsets `0`, `1`, `2` for each partition. Its 14-byte fetch budget returns two seven-byte records, then one record. An acknowledgement contains the next offset, not the last processed offset. Partition order is independent; Setu does not choose partitions for the application.

The long-poll example uses two client connections because calls on a single client are serialized. It requests a two-second fetch, produces a record from the other connection, then makes a 250 ms fetch at the partition end and receives an empty page. Its 35-second socket read timeout exceeds both waits. An append racing ahead of fetch registration is also returned safely.

## Embedded broker and memory expiry

```powershell
java '@config/jvm.options' -cp 'setu-server/target/lib/*' examples/SetuEmbeddedBroker.java
java '@config/jvm.options' -cp 'setu-server/target/lib/*' examples/SetuMemoryRetention.java
```

Both create and close their own memory broker. The embedded example prints the retained memory before acknowledgement and zero afterward. Direct calls bypass network authentication, ACLs and quotas.

The memory-retention example configures a 250 ms maximum age and deliberately leaves offset `0` unacknowledged. Background expiry reclaims the record; a subsequent fetch prints `OFFSET_OUT_OF_RANGE: Message retention expired; resume at offset 1`. It exits after observing the gap, with a five-second deadline to detect unexpected failures. This age limit is memory-only; file retention operates on whole daily cycles.

## Durable recovery

```powershell
java '@config/jvm.options' -cp 'setu-server/target/lib/*' examples/SetuFileRecovery.java
```

The program creates a unique directory under ignored `examples/target/`, writes records at offsets `0`–`2`, closes the broker, and opens another broker on the same directory. It reads the three recovered records and verifies that the next append receives offset `3`. It prints the directory and partition path and leaves the files available for inspection. FILE brokers also recover active consumer memberships and acknowledged progress; this example exercises record recovery without joining a consumer group. See [consumer recovery](../setu-core/README.md#broker-semantics) for the membership lifecycle and restart behavior.

## File retention

To start a broker with automatic seven-day cleanup and a separate example data directory:

```powershell
.\scripts\start-broker.ps1 --config examples/file-retention.properties
```

On Linux/macOS, use `bash ./scripts/start-broker.sh --config examples/file-retention.properties`. Run the client examples against this broker to create data. This configuration uses port `9092`, so stop the earlier broker first. Cleanup runs at startup and every minute, applies to all file partitions and ignores consumer acknowledgement progress.

For a self-contained storage-level demonstration:

```powershell
java '@config/jvm.options' -cp 'setu-server/target/lib/*' examples/SetuFileRetention.java
```

This creates a unique partition under `examples/target/`, appends one record, runs a seven-day sweep, closes and reopens the log, and verifies the retained offsets. The fresh daily file remains because the newest populated cycle is always kept. Lowering the duration does not make a fresh file expire immediately.

To prune an existing offline partition, pass its directory and the retention period in days. This command deletes eligible files; stop the owning broker first. Replace the sample path with the partition printed by the recovery example, or another partition you intend to prune:

```powershell
java '@config/jvm.options' -cp 'setu-server/target/lib/*' examples/SetuFileRetention.java 'examples/target/recovery-REPLACE/orders/partition-0' 7
```

The optional third argument sets the maximum record payload in bytes (default `1048576`); use a value large enough for the partition's existing records. Passing `0` days disables the sweep. The program prints file counts and the retained half-open offset range before and after cleanup, then verifies that the same range survives reopening. It never appends to a user-supplied partition.

For example, `[100, 350)` means the first available record is `100` and the next append will receive `350`. Direct storage reads clamp to the retained boundary. Broker fetches before it return `OFFSET_OUT_OF_RANGE`; see the [client handling example](../setu-client/README.md#handling-an-expired-file-offset).

File age is measured from the end of each UTC daily cycle. A one-day policy can retain a record for nearly two days plus the sweep delay. An idle partition's newest file remains until a later cycle receives data. See the [worked timeline](../docs/operations.md#file-retention-timeline) for a multi-day deletion example. The [storage retention tests](../setu-storage/src/test/java/io/setu/storage/log/ChronicleRetentionTests.java) use an injected clock to verify expiry without waiting days.

These examples close their clients, workers and brokers. The durable demos deliberately leave their isolated data directories for inspection; they do not recursively delete user data.
