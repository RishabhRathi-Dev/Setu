# Storage and batching experiment — 11 October 2026

File ingestion crossed the 1,000,000-record/s objective with larger produce batches while retaining the existing `FileChannel.force(true)` acknowledgement boundary. A 12.12-second stage with two producers and two consumers averaged **1,123,164 produced records/s**, using 512-byte payloads and 4,096-record batches. Consumer delivery averaged **243,949 records/s** in that stage, so backlog increased. This is an ingestion result, not a balanced end-to-end throughput result or a long-duration capacity guarantee. The 10,000,000-record/s memory objective remains open.

## Changes

- Memory logs store references in 1,024-record chunks. Reclaiming a prefix drops whole chunks and clears partial-chunk references without shifting surviving records. Payload and read-result defensive-copy guarantees remain intact.
- File logs use a primitive `long[]` position index, derive record lengths from adjacent positions, and fetch adjacent records in windows up to 1 MiB. A larger individual record gets its own read window.
- Segmented fetch accounting reads the payload length without cloning the payload solely to count its bytes.
- The server builds a runnable JAR with adjacent runtime libraries, properties configuration, a shutdown hook, and persistent rolling operational logs. The standalone launcher is plaintext; TLS/mTLS remains available through the existing secure embedding API.
- Capacity experiments accept explicit batch size, stage duration, and concurrency limits. The published benchmark profile still defaults to 256-record batches, two-second stages, and an unlimited concurrency ramp.

The capacity workload bypasses security quotas. Raising quota rates would not change these measurements. Packaging supplies a runnable distribution on the JVM; it does not account for the throughput gains.

## Same-host storage comparison

Baseline storage sources came from commit `c44348554f4ac0853c8ac55fabd4e2e9cac2b028` and were compiled into a separate class directory. Both versions used the same broker, protocol, client, and workload code, on Windows with OpenJDK 25.0.2. Broker and load clients shared one JVM with a 768 MiB maximum heap and used localhost TCP.

The bounded comparison repeated `QuickBrokerChaosBenchmarkTests` seven times per version in one JVM, discarded two warmup runs, and took the median of five measured runs. Each round used 16 producers, 16 consumers, 20,000 records, 256-byte values, and 256-record batches. Delivery rates count every consumer delivery, including fanout; they are not counts of distinct ingested records.

| Bounded metric | Before | After | Observation |
| --- | ---: | ---: | --- |
| Memory produce records/s | 3,355,198 | 3,364,172 | Essentially flat |
| Memory consumer deliveries/s | 2,327,277 | 2,398,922 | About 3% higher |
| File produce records/s | 282,065 | 297,066 | About 5% higher |
| File consumer deliveries/s | 468,487 | 2,012,774 | About 4.3× higher |

The unchanged 512-byte/256-record progressive file workload peaked at **254,469 produced records/s before** and **411,261 after**, approximately 62% higher. Both file ramps ultimately reached heap exhaustion at 1,024 producers and 1,024 consumers. The memory ramp was sensitive to backlog and heap exhaustion, so it does not establish a memory-capacity gain. An exploratory packed-payload memory layout was replaced after repeated bounded measurements exposed extra fetch-copy overhead; all final bounded results above use chunked references.

Raw observations: [bounded before](bounded-before.txt), [bounded after](bounded-after.txt), [file capacity before](capacity-file-before.txt), [file capacity after](capacity-file-after.txt).

## Batch-size experiment

These file-storage experiments capped the ramp at two producers and two consumers. Every value was 512 bytes; every touched file segment was forced before acknowledging its produce batch.

| Records per produce batch | Stage duration | Peak produce records/s | Consumer deliveries/s at peak | Approximate p99 request latency |
| --- | --- | ---: | ---: | ---: |
| 1,024 | 4 seconds | 784,218 | 473,667 | 8.389 ms |
| 4,096 | 4 seconds | 1,218,517 | 270,540 | 16.777 ms |
| 4,096 | 12 seconds | 1,123,164 | 243,949 | 16.777 ms |

Each stopped at `CONFIGURED_LIMIT`, which identifies an intentionally bounded experiment, not the machine's capacity limit. The larger batches amortize durable-sync cost over more records, but their consumers fall behind. The latency histogram is approximate and covers the workload's timed produce/fetch/ack operations; it is not a dedicated producer-only latency metric.

Raw observations: [1,024-record batches](batch-1024.txt), [4,096-record batches](batch-4096.txt), [longer 4,096-record run](batch-4096-long.txt).

Reproduce the longer file experiment from the repository root with Java 25:

```powershell
.\mvnw.cmd -pl setu-benchmark -am `
  '-Dtest=ProgressiveCapacityChaosTests#progressivelyRampsFileProducersAndConsumersUntilTheRuntimeLimit' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dsetu.benchmark.batch.records=4096' `
  '-Dsetu.benchmark.max.concurrency=2' `
  '-Dsetu.benchmark.stage.millis=12000' test
```

## Validation and remaining work

All 50 unit, integration, and bounded chaos tests passed. Three bounded file-capacity runs passed. The packaged JAR passed produce/fetch and forced-restart recovery checks for both blocking and selector transports, and its operational logs persisted on disk. Storage tests cover partial/full chunk reclamation, stable offsets, defensive copies, empty-record limits, batch prevalidation, multi-window fetches, large records, byte budgets, and restart recovery.

Next work should address bounded buffering and request backpressure so ingestion cannot overwhelm consumers or the JVM, reduce protocol copy overhead, and measure partition parallelism. Feature gaps still include durable consumer offsets/control-plane metadata, checksums and retention, configurable acknowledgement modes, consumer-group assignment/rebalance, and metrics. Persistent message segments already existed; operational rolling logs are now connected to the standalone launcher.

See [standalone server instructions](../../../setu-server/README.md#standalone-jar) for the runnable distribution.
