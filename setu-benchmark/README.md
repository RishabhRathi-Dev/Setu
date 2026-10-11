# Setu benchmarks and chaos tests

All load and failure testing lives under `src/test/java/io/setu/benchmark/chaos`. CI runs every chaos test against the actual TCP server, synchronous client, and broker in both storage modes. `QuickBrokerChaosBenchmarkTests` drives sixteen producers and sixteen independent consumer members through 20,000 256-byte records in 256-record produce batches, exercises fetch paging and file-segment rotation, abandons one consumer with an outstanding acknowledgement, verifies expiry and continued progress, and restarts the broker. `BrokerLimitsChaosTests` drives sixteen producers and sixteen consumers through 16,000 records while exercising size boundaries and segment rotation. Both bounded workloads have five-minute deadlines; the acknowledgement expiry remains 250 ms.

`ProgressiveCapacityChaosTests` launches a separate JVM with a 768 MiB maximum heap for each storage mode. Each run doubles producer and consumer connections at every stage, continuously produces 256-record batches and fetches/acknowledges records over TCP, and reports per-stage throughput, approximate p99 request latency, process CPU, heap use, and the capacity stop reason. It continues until sustained process CPU saturation, JVM heap exhaustion, or an operating-system resource limit ends the ramp. The child process contains heap OOM so it can be reported without taking down Maven or the host. The README badges show each mode's terminal stage, stop reason, peak observed producer/consumer rates, CPU, heap, and p99 latency. These measurements vary by machine and are not pass/fail throughput thresholds.

The default profile remains unchanged. For controlled experiments, set `setu.benchmark.batch.records` (1–10,000, default 256), `setu.benchmark.stage.millis` (250–60,000, default 2,000), and `setu.benchmark.max.concurrency` (zero for the unbounded ramp, or a power of two up to 32,768). The test forwards these properties into its isolated worker JVM. Each worker reports `CAPACITY_CONFIG` so its workload is explicit. A bounded ramp ends with `CONFIGURED_LIMIT`; it does not establish the host's terminal capacity. This plaintext workload bypasses security quotas.

The 11 October storage/batching experiment exceeded 1M produced 512-byte records/s with larger batches while consumers accumulated backlog; those results use a different profile from the published default benchmark badges. Local working notes stay in the ignored `docs/` directory, and diagnostic captures belong under the ignored `logs/` directory.

CI runs the matching 4,096-record, 12-second file experiment with up to two producers/consumers as a separate step before capacity exhaustion. Its results appear in the workflow log. The badge input continues to contain only the default profile and bounded chaos workload, keeping historical capacity comparisons consistent.

The top-level README uses self-contained Shields badge URLs, so metrics remain visible even when the repository does not expose raw files publicly. After a successful master run, the workflow parses the progressive test output, refreshes the capacity and peak-rate JSON, updates the badge URLs in the README, and pushes the data with `[skip ci]`. Pull request and non-master runs do not update the published badges.

Functional end-to-end requirements belong to `setu-integration-tests`; crash, expiry, malformed traffic, concurrent stress, and resource exhaustion are benchmark chaos cases. CI runs unit tests, all integration tests, then every bounded chaos test. It runs progressive capacity tests last so their CPU and memory exhaustion cannot interfere with bounded test deadlines. Maven builds upstream modules to resolve reactor dependencies, while Surefire filters execution to the integration or chaos classes for each stage.

Run it from the repository root with Java 25:

```powershell
.\mvnw.cmd -pl setu-benchmark -am '-Dtest=*ChaosTests,!ProgressiveCapacityChaosTests,QuickBrokerChaosBenchmarkTests' -Dsurefire.failIfNoSpecifiedTests=false test
.\mvnw.cmd -pl setu-benchmark -am '-Dtest=ProgressiveCapacityChaosTests' -Dsurefire.failIfNoSpecifiedTests=false test
```

