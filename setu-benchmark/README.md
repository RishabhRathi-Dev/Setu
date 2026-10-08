# Setu benchmarks and chaos tests

All load and failure testing lives under `src/test/java/io/setu/benchmark/chaos`. CI first runs `QuickBrokerChaosBenchmarkTests` against the actual TCP server, synchronous client, and broker in both storage modes. It drives sixteen producers and sixteen independent consumer members through 20,000 256-byte records, exercises fetch paging and file-segment rotation, abandons one consumer with an outstanding acknowledgement, verifies expiry and continued progress, and restarts the broker.

`ProgressiveCapacityChaosTests` launches a separate JVM with a 768 MiB maximum heap for each storage mode. Each run doubles producer and consumer connections at every stage, continuously produces and fetches/acknowledges records over TCP, and reports per-stage throughput, approximate p99 request latency, process CPU, heap use, and the capacity stop reason. It continues until sustained process CPU saturation, JVM heap exhaustion, or an operating-system resource limit ends the ramp. The child process contains heap OOM so it can be reported without taking down Maven or the host. The latest local run reached 4,096 producers and 4,096 consumers in each mode, then reported `JVM_OOM`; its best observed consumer fan-out rates were about 1.51M records/s in memory mode and 456K records/s in file mode. These measurements vary by machine and are not pass/fail throughput thresholds.

Functional end-to-end requirements belong to `setu-integration-tests`; crash, expiry, malformed traffic, concurrent stress, and resource exhaustion are benchmark chaos cases. CI runs unit tests, functional integration tests, then this chaos and capacity suite.

Run it from the repository root with Java 25:

```powershell
.\mvnw.cmd -pl setu-benchmark -am test
```

