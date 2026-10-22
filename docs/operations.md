# Operations

Setu runs as a single broker process. The standalone launcher provides a local plaintext listener; embed the [secure server factory](../setu-server/README.md#secure-broker-wiring-example) for TLS/mTLS. [Configuration](configuration.md) lists every supported setting.

## Build and distribute

Use JDK 26 and run from the repository root:

```powershell
.\mvnw.cmd -pl setu-server -am package
```

Use `bash ./mvnw` on Linux/macOS. Packaging produces:

```text
setu-server/target/
├── setu-server-1.0.0-SNAPSHOT.jar
└── lib/                            # Runtime dependency JARs
```

Keep the application JAR and its adjacent `lib/` together. Include `config/jvm.options` and your properties file in the distribution, and choose a consistent working directory for data and logs. The JAR manifest identifies the entry point and relative dependency classpath.

## Start and stop

```powershell
.\scripts\start-broker.ps1 --config config/server.properties
```

On Linux/macOS:

```bash
bash ./scripts/start-broker.sh --config config/server.properties
```

The launchers locate the application JAR and shared JVM flags from their own repository location. Configuration arguments and runtime paths remain relative to the caller's working directory. A direct PowerShell launch is:

```powershell
java '@config/jvm.options' -jar setu-server/target/setu-server-1.0.0-SNAPSHOT.jar --config config/server.properties
```

Look for `Setu listening on` with the bound address, storage mode, and transport. Run the [quickstart client](../examples/SetuQuickstart.java) to verify a complete produce/fetch/ack exchange; it writes two records to a local `quickstart` topic.

Ctrl+C runs the shutdown hook: acceptance stops, tracked connections close, accepted log writes drain, and the broker closes its partition logs. In an embedded application, close the server before the broker. Keep one broker owner per partition; an exclusive lock prevents simultaneous file-mode ownership.

## Durable data and recovery

File-mode layout is:

```text
data/
└── <topic>/
    └── partition-<id>/
        ├── .setu.lock
        ├── metadata.cq4t
        └── <daily-cycle>.cq4
```

On startup, Setu validates committed documents and rebuilds its logical-offset index. A malformed committed document fails startup. An incomplete native document does not consume a recovered offset. Preserve the failing data for investigation; do not edit queue bytes or discard later files to force startup.

For a consistent offline copy, stop the broker cleanly and copy the complete data root, including `@consumer-state/`, metadata and all partition files. Active-file snapshot/backup coordination is not implemented. Restore into a separate root with one owner, use a compatible application/JDK, and verify recovered topics, records, memberships and acknowledged offsets before switching applications. File consumer state recovers with fresh inactivity deadlines; ACLs and quotas must be recreated by applications. The consumer journal supports [automatic metadata checkpoints](journal-checkpoints.md); include its CURRENT pointer and selected generation in the offline copy and do not downgrade a checkpointed directory. Monitor its disk usage and recovery time. New writes use checksummed format 2, which older binaries cannot read; preserve a pre-upgrade backup if rollback is required.

Older length-prefixed `.log` partitions require the [offline migration procedure](../setu-storage/README.md#migrating-existing-data). Import into a new, nonexistent directory outside the source root; the importer preserves the source. Verify the import before changing `data.dir`.

Monitor disk space: daily roll and mapping block size do not cap disk usage. Set `file.retention.millis` to enable automatic age-based pruning of completed daily files; zero disables it. Cleanup runs at startup and every minute and always keeps the newest populated file, including on idle partitions. Age is measured from the end of a daily cycle, so individual records can remain up to an extra day plus the sweep delay. Consumer acknowledgements do not delay expiry. Fetching expired data returns `OFFSET_OUT_OF_RANGE` with the earliest retained offset. Cleanup briefly pauses partition I/O while releasing mappings and reopening the queue. Deletion failures are logged and retried on later sweeps. Preserve `.setu-retention` alongside queue files in backups; do not manually delete or edit it. There is no supported online topic-deletion API. A durable success means the local force boundary completed; it does not provide replication or protection against a failed device. Lost replies can leave writes with uncertain outcomes.

### File retention timeline

Suppose `orders/0` has the following UTC daily cycles and `file.retention.millis=172800000` (two days). A sweep at **2026-10-20 00:01 UTC** evaluates the files this way:

| Cycle | Logical offsets | Cycle ends | Cleanup result |
| --- | --- | --- | --- |
| October 17 | 0–99 | October 18, 00:00 UTC | Expired: cycle end is at least two days old |
| October 18 | 100–199 | October 19, 00:00 UTC | Retained: cycle end is only one day old |
| October 19 | 200–299 | October 20, 00:00 UTC | Retained: cycle end is one minute old |
| October 20 | 300–349 | October 21, 00:00 UTC | Retained: newest populated cycle |

After cleanup, `firstOffset()` is `100` and `nextOffset()` is `350`. Fetching offset `0` returns `OFFSET_OUT_OF_RANGE` with `Message retention expired; resume at offset 100`. Fetching `100` can read the remaining history. The next append receives offset `350`, including after restart. Consumer acknowledgements and active memberships do not preserve the expired October 17 file. The [client example](../setu-client/README.md#handling-an-expired-file-offset) shows how to handle the response.

For an idle partition, suppose October 17 is its only populated cycle. Cleanup keeps that file even on October 30. Once a new append creates an October 30 cycle, a later sweep can remove October 17. Retention therefore does not impose a strict maximum record age or disk-size cap. Small values such as `file.retention.millis=1000` still operate on daily files; they do not delete individual records after one second.

To stop automatic expiry, set `file.retention.millis=0` and restart with that configuration. Existing retained offsets remain in force. When restoring an offline backup for inspection, start with retention disabled if the goal is to inspect all records in that backup before allowing cleanup.

## Logs and diagnosis

`logs/setu.log` contains operational events. Logs rotate daily or at 100 MB, retain 14 days, and cap archives at 2 GB. Set `SETU_LOG_DIR` and `SETU_LOG_LEVEL`, or pass `-Dsetu.log.level` on a direct JVM launch. The system property takes precedence. Correlation IDs, session IDs, errors, and consumer-expiry details help connect client failures with server events.

For a local JFR capture using the existing TCP workload:

```powershell
python scripts/run-jfr.py --storage-mode FILE
```

The tool builds the benchmark classes, writes a new recording directory under `logs/jfr/`, and validates completed measured-stage markers. It is a diagnostic smoke workload, with clients and broker in one JVM and no OS resource quota. The [benchmark guide](../setu-benchmark/README.md#local-jfr-capture) explains output files, recording limits, and full qualification profiles.

| Symptom | Check / response |
| --- | --- |
| `java` missing or compiler release unsupported | Set `JAVA_HOME` and `PATH` to JDK 26; check `java -version` and Maven's JDK |
| JAR missing / dependency class not found | Rebuild `setu-server` with `-am package`; retain adjacent `lib/` |
| Chronicle access/native error | Launch with the shared `config/jvm.options` |
| Existing AOT cache rejected | Launch with `--no-aot`, then regenerate the cache for the current build |
| Address already in use | Stop the existing listener or select another `bind.port` |
| Partition lock failure | Verify no other broker owns that data root |
| Unknown property / invalid enum | Check exact supported keys and case in the [configuration reference](configuration.md) |
| `QUOTA_EXCEEDED` | Inspect memory-log/write-queue admission, transport budgets, and secure principal quotas; reduce batch/load or advance consumers |
| Memory records missing | Join before producing; check member expiry, acknowledgements, and optional lossy retention |
| Empty fetch | Check offset, available data, and byte budget; an oversized first record returns an empty page |
| `OFFSET_OUT_OF_RANGE` | Check partition end, timed memory expiry, and file retention; inspect the reported resume boundary before choosing an application resume point |
| Member no longer registered | Rejoin and resume according to storage semantics; heartbeats do not replace acknowledgements |
| Slow long poll times out at client | Set client read timeout above the requested wait, at most 30 seconds plus headroom |
| `AUTHORIZATION_DENIED` after valid mTLS | Register exact principal/resource/permission grants in the embedding application |
| Lost produce response | Treat outcome as uncertain; automatic retry can duplicate records |

If these checks do not resolve the problem, follow [support and issue reporting](../SUPPORT.md) and include the revision, environment, configuration, and a minimal reproduction. Use the [security policy](../SECURITY.md) for suspected vulnerabilities.

## AOT cache lifecycle

```powershell
.\scripts\start-broker.ps1 --train-aot
.\scripts\start-broker.ps1 --config config/server.properties
.\scripts\start-broker.ps1 --no-aot --config config/server.properties
```

Use the corresponding Bash launcher on Linux/macOS. Training generates `setu-server/target/setu-server.aot` through real plaintext and mTLS exchanges in both storage modes, including deliberate rejection cases. Existing caches are used in strict mode unless bypassed. Generate a new cache after changes to the JDK, platform, application, or dependencies. Caches are local build artifacts and remain untracked.

On the tested JDK, Chronicle's module flags disable AOT class linking; shared application classes still load from the cache. JIT compilation remains responsible for native application code. See [the server guide](../setu-server/README.md#standalone-jar) for training and CI details.

## Metrics and packaged distributions

Enable `operations.port` for the read-only loopback [metrics/readiness endpoint](observability.md). Normal logging defaults to INFO; DEBUG request diagnostics are opt-in.

Build with the documented Maven command, then run `python scripts/package-distribution.py`. Extract `target/setu-<version>.zip`, enter its top-level directory and run `scripts/start-broker.ps1 --config config/server.properties` or `bash scripts/start-broker.sh --config config/server.properties`. JDK 26 remains required; Maven and the source tree are not. Keep the root server JAR and adjacent `lib/` together. `SHA256SUMS` identifies each packaged file; the archive also has a SHA-256 sidecar. CI retains verified distributions as workflow artifacts; no public Maven Central release is implied.
