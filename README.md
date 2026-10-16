# Setu

Setu is a **single-node message broker in Java 26** with topics, partitions, ordered offsets and a binary TCP protocol. **OpenHFT Chronicle Queue is the default durable storage engine**; an explicit in-memory mode supports volatile workloads.

[Build and CI](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml) · [Server guide](setu-server/README.md) · [Client API](setu-client/README.md) · [Benchmarks](setu-benchmark/README.md)

## Quick start

Install a Java 26 JDK, set `JAVA_HOME`, and put its `bin` directory on `PATH`. The checked-in Maven Wrapper downloads Maven on first use.

From the repository root on Windows:

```powershell
.\mvnw.cmd -pl setu-server -am package
.\scripts\start-broker.ps1 --config config/server.properties
```

On Linux or macOS:

```bash
bash ./mvnw -pl setu-server -am package
bash ./scripts/start-broker.sh --config config/server.properties
```

The broker listens on **127.0.0.1:9092**, stores messages under `data/`, and writes rolling operational logs under `logs/`. Ctrl+C closes the broker. The standalone launcher serves plaintext; use [secure embedding](setu-server/README.md#secure-broker-wiring-example) for TLS/mTLS listeners.

Both launchers load [config/jvm.options](config/jvm.options), including ZGC, string deduplication and Chronicle's required module/native access. Optional AOT training is available through `--train-aot`; regenerate the cache after changing the application, dependencies or JDK. See the [launch and AOT instructions](setu-server/README.md#standalone-jar).

Configure the broker in [config/server.properties](config/server.properties):

| Setting | Default | Purpose |
| --- | --- | --- |
| `storage.mode` | `FILE` | Chronicle Queue; use `IN_MEMORY` for volatile storage |
| `data.dir` | `data` | Topic and partition data root |
| `transport` | `blocking` | Virtual thread per connection; `selector` supports plaintext multiplexing |
| `record.max.bytes` | 1 MiB | Maximum record payload |
| `queue.block.bytes` | 64 MiB | Chronicle mapping block size, with a 64 MiB minimum |
| `memory.log.max.bytes` | 256 MiB | Shared retained-log budget in memory mode |
| `ack.timeout.millis` | 30,000 | Deadline for acknowledging fetched records |
| `consumer.timeout.millis` | 60,000 | Consumer inactivity deadline |

Use [SetuClient](setu-client/README.md#example) to create topics, produce records and fetch by offset. It supports single-record and batched writes, long polling, membership, heartbeats and acknowledgements. Broker errors are returned as structured responses; callers must check `errorCode()`.

## Current implementation snapshot

- **Messaging:** topic creation and metadata, partitioned append/fetch, single and batch produce, group membership and offset acknowledgements.
- **Protocol:** version 1 binary frames, correlation IDs, strict validation, bounded request/response admission and structured errors.
- **Concurrency:** immutable payload ownership, CAS-based memory publication and write admission, bounded group commit, and coordinated consumer bookkeeping.
- **Security:** embedded TLS/mTLS listeners, DNS SAN client identity, default-deny exact ACL grants, and per-principal message/byte quotas.
- **Verification:** unit tests, real TCP/mTLS integration tests, process-kill recovery, bounded chaos, JFR profiles and AOT cache validation.

The broker runs in one process. Consumer membership, committed offsets, ACL grants and quota policies are process-local. Replication, failover, automatic consumer-group assignment/rebalancing, file retention and payload checksums are not implemented. The client is synchronous and has no automatic retry, reconnect or idempotent produce.

## Architecture

```mermaid
flowchart LR
    client[SetuClient] --> server["setu-server<br/>TCP / TLS, framing and dispatch"]
    server --> core["setu-core<br/>Protocol v1 and DefaultBroker"]
    server -.-> security["setu-security<br/>Identity, ACLs and quotas"]
    core --> storage["setu-storage<br/>MessageLog"]
    storage --> file["FILE<br/>Chronicle Queue"]
    storage --> memory["IN_MEMORY<br/>Immutable chunks"]
```

The server owns transport and request policy, core owns broker operations and shared protocol types, and storage owns the mapping from logical offsets to physical records. PostgreSQL is not part of the runtime.

| Module | Responsibility |
| --- | --- |
| [setu-core](setu-core/README.md) | Broker API, topics/partitions, consumer bookkeeping and shared wire codec |
| [setu-storage](setu-storage/README.md) | Chronicle Queue, in-memory logs, admission, group commit and recovery |
| [setu-server](setu-server/README.md) | Standalone launcher, TCP transports, secure dispatch and AOT training |
| [setu-client](setu-client/README.md) | Synchronous plaintext and TLS client |
| [setu-security](setu-security/README.md) | Certificate identity, authorization and token-bucket quotas |
| [setu-integration-tests](setu-integration-tests/README.md) | Functional requirements over real TCP and mTLS |
| [setu-benchmark](setu-benchmark/README.md) | Load measurement, JFR, crash recovery and chaos workloads |

## Storage and backpressure

### Durable mode: `FILE`

Each partition uses Chronicle Queue 2026.6 with native daily rolled `.cq4` files under `<data.dir>/<topic>/partition-<id>/`. Setu exposes dense, zero-based record offsets independently of Chronicle's physical indexes. The mapping block size does not limit daily file size or enable retention.

A bounded writer groups adjacent produce requests. Success is returned after JDK mapped-file and channel forces cover touched queue files, indexes and roll metadata. The adapter uses this explicit force path because the resolved OpenHFT native Windows sync is a no-op. A partition has one broker owner. Durable writes serialize, while reads of previously committed records can proceed during forcing.

Restart validates committed documents and rebuilds Setu's sparse logical-offset index. Malformed committed data fails startup. Failed or lost acknowledgements can have an uncertain outcome; this is local persistence without replication or exactly-once delivery.

Existing length-prefixed `.log` data requires [offline migration into a fresh data root](setu-storage/README.md#migrating-existing-data). The importer preserves the source files.

### Volatile mode: `IN_MEMORY`

Complete batches publish as immutable chunks with CAS. Group members start at their join-time offsets; acknowledgements and member removal allow reclamation through the slowest registered member. Logical offsets remain monotonic as prefixes are removed. Restart discards topics and messages. Optional time-based expiry is lossy and disabled by default.

The memory budget reserves whole batches before publication. Memory-log and durable write-queue saturation return `QUOTA_EXCEEDED`; clients can retry with backoff after consumer progress. Transport buffers have separate admission limits and can close a connection when no error reply can be admitted. These limits account for specific allocations, rather than imposing a total process-memory cap. See [storage semantics](setu-storage/README.md) and [server limits](setu-server/README.md).

## Security

`SecureServerFactory` composes mTLS authentication, request authorization and quotas with the broker. Client identities come from DNS subject alternative names; ACLs match exact principal/resource/permission grants and deny by default. TLS clients verify the broker hostname. The standalone properties launcher does not configure this secure path.

See the [security module](setu-security/README.md) and [server integration guide](setu-server/README.md) for embedding. Development certificate scripts are available under `scripts/generate-certs.ps1` and `scripts/generate-certs.sh`.

## Tests

Run production-module tests and functional TCP/mTLS integration tests without starting the capacity-exhaustion workload:

```powershell
.\mvnw.cmd -pl setu-integration-tests -am test
```

Use `bash ./mvnw` in place of `.\mvnw.cmd` on Linux/macOS. Python 3 is needed for benchmark tooling and its report tests:

```bash
python -m unittest discover -s scripts -p 'test_benchmark*.py'
```

[CI](.github/workflows/ci.yml) also runs bounded chaos, isolated load checks, sustained smoke, node-target measurements with JFR, and secure AOT cache validation. Detailed benchmark commands and qualification rules live in the [benchmark guide](setu-benchmark/README.md).

## Performance

The reference targets are **500K records/s in memory** and **250K records/s durable**, using 1 KiB records. Qualification requires sustained unique consumption, measured latency, stable heap, bounded CPU and zero request errors. Targets are goals, not guarantees.

The local Chronicle Windows diagnostic measured approximately **55K durable records/s** with real mapped-file forcing and did not qualify. Earlier custom-backend results do not establish Chronicle performance. See the [measurements and methods](setu-benchmark/README.md#default-openhft-storage).

The generated badges and diagnostics retain their measured source revision and qualification status. Read results against that revision: measurements from the former custom backend do not qualify the Chronicle engine. CI refreshes these blocks from subsequent measurements.

<!-- benchmark-metrics-badges:start -->
[![in-memory: 500.00K/s target, 261.83K/s CI, 52% unqualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38936442276)
[![file: 250.00K/s target, 86.16K/s CI, 34% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38936442276)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest recorded target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 14638.12 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38936442276)
[![memory resources: 100% CPU, 256 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38936442276)
[![memory JFR: 0.86 GiB/s alloc, 41.5 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38936442276)
[![file p99: 19545.46 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38936442276)
[![file resources: 59% CPU, 128 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38936442276)
[![file JFR: 0.40 GiB/s alloc, 6.6 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38936442276)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `75760a4c69339dd26e776ca1a2b6bd960cbf2e13`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38936442276).

Runner: AMD EPYC 9V45 96-Core Processor; 1.6 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

Runner: AMD EPYC 7763 64-Core Processor; 1.6 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85%, heap <=1 GiB and heap slope <=1 MiB/s.

| Mode | Target/s | Median measured/s | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 261,828 | 14638.121 | 256 | 99.5 | False |
| FILE | 250,000 | 86,161 | 19545.457 | 128 | 59.3 | False |

| Mode | Repeat | Load | Measured/s | Consumed/s | p99 ms | Heap slope MiB/s | Healthy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 77,952 | 77,969 | 11198.792 | 0.169 | False |
| FILE | 1 | 80% | 84,531 | 84,531 | 17137.926 | 0.152 | False |
| FILE | 1 | 100% | 85,649 | 85,658 | 19545.457 | 0.160 | False |
| FILE | 2 | 50% | 85,914 | 85,931 | 9563.013 | -0.022 | False |
| FILE | 2 | 80% | 85,939 | 85,956 | 16936.600 | 0.189 | False |
| FILE | 2 | 100% | 86,195 | 86,212 | 19478.348 | 0.156 | False |
| FILE | 3 | 50% | 84,429 | 84,446 | 9982.444 | -0.054 | False |
| FILE | 3 | 80% | 85,641 | 85,658 | 16986.931 | 0.048 | False |
| FILE | 3 | 100% | 86,161 | 86,161 | 19478.348 | 0.285 | False |
| IN_MEMORY | 1 | 50% | 245,879 | 245,879 | 591.921 | 0.082 | False |
| IN_MEMORY | 1 | 80% | 292,454 | 292,437 | 7977.566 | -0.099 | False |
| IN_MEMORY | 1 | 100% | 253,602 | 253,611 | 14638.121 | 0.220 | False |
| IN_MEMORY | 2 | 50% | 249,993 | 250,974 | 217.055 | -0.729 | False |
| IN_MEMORY | 2 | 80% | 269,065 | 269,073 | 9714.008 | -0.277 | False |
| IN_MEMORY | 2 | 100% | 311,518 | 311,561 | 11198.792 | 0.462 | False |
| IN_MEMORY | 3 | 50% | 249,993 | 251,017 | 203.555 | -0.611 | False |
| IN_MEMORY | 3 | 80% | 286,447 | 286,404 | 8422.162 | -0.169 | False |
| IN_MEMORY | 3 | 100% | 261,828 | 261,820 | 14151.582 | 0.239 | False |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | throughput 77,952/s <95% of offered 125,000/s; p99 11198.792 ms outside [0,10) ms |
| FILE | 1 | 80% | throughput 84,531/s <95% of offered 200,000/s; p99 17137.926 ms outside [0,100) ms |
| FILE | 1 | 100% | throughput 85,649/s <95% of offered 250,000/s; p99 19545.457 ms outside [0,100) ms |
| FILE | 2 | 50% | throughput 85,914/s <95% of offered 125,000/s; p99 9563.013 ms outside [0,10) ms |
| FILE | 2 | 80% | throughput 85,939/s <95% of offered 200,000/s; p99 16936.600 ms outside [0,100) ms |
| FILE | 2 | 100% | throughput 86,195/s <95% of offered 250,000/s; p99 19478.348 ms outside [0,100) ms |
| FILE | 3 | 50% | throughput 84,429/s <95% of offered 125,000/s; p99 9982.444 ms outside [0,10) ms |
| FILE | 3 | 80% | throughput 85,641/s <95% of offered 200,000/s; p99 16986.931 ms outside [0,100) ms |
| FILE | 3 | 100% | throughput 86,161/s <95% of offered 250,000/s; p99 19478.348 ms outside [0,100) ms |
| IN_MEMORY | 1 | 50% | p99 591.921 ms outside [0,5) ms; CPU 99.1% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 80% | throughput 292,454/s <95% of offered 400,000/s; p99 7977.566 ms outside [0,50) ms; CPU 99.5% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 100% | throughput 253,602/s <95% of offered 500,000/s; p99 14638.121 ms outside [0,50) ms; CPU 99.4% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 50% | p99 217.055 ms outside [0,5) ms; CPU 91.5% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 80% | throughput 269,065/s <95% of offered 400,000/s; p99 9714.008 ms outside [0,50) ms; CPU 99.4% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 100% | throughput 311,518/s <95% of offered 500,000/s; p99 11198.792 ms outside [0,50) ms; CPU 99.5% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 50% | p99 203.555 ms outside [0,5) ms; CPU 91.4% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 80% | throughput 286,447/s <95% of offered 400,000/s; p99 8422.162 ms outside [0,50) ms; CPU 99.2% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 100% | throughput 261,828/s <95% of offered 500,000/s; p99 14151.582 ms outside [0,50) ms; CPU 99.4% outside 0–85% of allocated budget |
<!-- benchmark-capacity-details:end -->

</details>

<details>
<summary>Historical capacity diagnostics</summary>

These older exhaustion/fanout measurements do not qualify sustained throughput.

<!-- benchmark-historical-badges:start -->
[![memory peak rates: 433.95K prod/s · 872.32K consumed/s](benchmark-badges/memory-throughput.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![file peak rates: 205.50K prod/s · 178.53K consumed/s](benchmark-badges/file-throughput.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![memory capacity: 2,048×2,048 · OS limit · peak CPU 100% · 1252 MB · p99 2.1s](benchmark-badges/memory-capacity.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![file capacity: 256×256 · broker/client limit · peak CPU 100% · 750 MB · p99 1.1s](benchmark-badges/file-capacity.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![memory JFR: stage 12 · 1.31 GiB/s alloc · 1.8ms GC](benchmark-badges/memory-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![file JFR: stage 9 · 1.92 GiB/s alloc · 0.0ms GC](benchmark-badges/file-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
<!-- benchmark-historical-badges:end -->

</details>
