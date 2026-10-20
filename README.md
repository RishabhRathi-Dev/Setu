# Setu

**A single-node Java message broker with partitioned logs, durable acknowledgements, and a binary TCP protocol.**

[CI](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml) · [Architecture](docs/architecture.md) · [Configuration](docs/configuration.md) · [Operations](docs/operations.md) · [Contributing](CONTRIBUTING.md)

Setu stores ordered records in topic partitions and exposes explicit produce, fetch, and consumer-progress operations. The default `FILE` engine uses OpenHFT Chronicle Queue; `IN_MEMORY` provides bounded, volatile storage. The implementation includes blocking and selector TCP transports, a synchronous Java client, and an embedded mTLS security pipeline.

Setu is under active development. It runs as one broker process, with no replication or failover. Consumer state and security policies are process-local. See [implementation boundaries](#implementation-boundaries) before choosing a deployment model.

## Contents

- [Quick start](#quick-start)
- [Architecture](#architecture)
- [Messaging and storage](#messaging-and-storage)
- [Security](#security)
- [Configuration](#configuration)
- [Build and verification](#build-and-verification)
- [Repository guide](#repository-guide)
- [Implementation boundaries](#implementation-boundaries)
- [Performance](#performance)

## Quick start

### 1. Build and start the broker

Install **JDK 26**, set `JAVA_HOME` to its directory, and add its `bin/` directory to `PATH`. The Maven Wrapper downloads the pinned Maven distribution on first use; no separate Maven installation is required. Run these commands from the repository root.

**Windows / PowerShell**

```powershell
.\mvnw.cmd -pl setu-server -am package
.\scripts\start-broker.ps1 --config config/server.properties
```

**Linux / macOS**

```bash
bash ./mvnw -pl setu-server -am package
bash ./scripts/start-broker.sh --config config/server.properties
```

Wait for `Setu listening on` in the console. Defaults are plaintext **127.0.0.1:9092**, durable data in `data/`, and rolling logs in `logs/`. Ctrl+C closes connections and drains accepted storage writes. Keep the JAR and its adjacent `setu-server/target/lib/` dependencies together.

Both launchers load [config/jvm.options](config/jvm.options), including ZGC, string deduplication, and Chronicle's module/native access. [Operations](docs/operations.md) covers direct JAR launches, logging, shutdown, recovery, and optional AOT training.

### 2. Produce and consume records

In a second terminal, run the checked-in [Java example](examples/SetuQuickstart.java). The command works in PowerShell and Bash with JDK 26 on `PATH`:

```powershell
java '@config/jvm.options' -cp 'setu-server/target/lib/*' examples/SetuQuickstart.java
```

On a fresh topic, it prints:

```text
0: hello, Setu
1: ordered by partition
```

The example creates a topic, joins a consumer group, appends a batch, fetches it, acknowledges the next offset, and leaves the group. It can be rerun; offsets increase in durable mode. Optional arguments select a host and port. Use a dedicated local broker while running it.

For an application, build with `-pl setu-client -am install` and depend on `org.rishabh:setu-client:1.0.0-SNAPSHOT`. The [client guide](setu-client/README.md) documents error handling, TLS, timeouts, and ownership.

## Architecture

```mermaid
flowchart LR
    app[Java application] --> client[SetuClient]
    client --> transport["TCP transport<br/>Blocking or selector"]
    transport --> framing["Frame admission<br/>and protocol v1"]
    framing --> dispatch[Request dispatch]
    dispatch --> broker[DefaultBroker]
    broker --> topics["Topics and partitions<br/>Consumer progress"]
    topics --> logs[MessageLog]
    logs --> file["FILE<br/>Group commit + Chronicle Queue"]
    logs --> memory["IN_MEMORY<br/>Immutable chunks + budget"]
    security["Embedded security<br/>mTLS, exact ACLs, quotas"] -.-> dispatch
```

Transport manages sockets and buffer admission; core owns protocol and broker semantics; storage maps logical offsets to physical records. The embedded secure server authenticates connections and authorizes requests before dispatch. The standalone properties launcher serves plaintext.

The [architecture guide](docs/architecture.md) includes module dependencies, a durable-write sequence, consumer lifecycle, backpressure, and restart behavior. The [wire reference](setu-core/README.md#protocol-v1) describes framing and command IDs.

## Messaging and storage

Records are opaque byte arrays. Ordering and dense, zero-based offsets apply **within each partition**. Applications select partitions explicitly. Batch produce returns the first assigned offset; fetch returns whole records within a byte budget. Consumer acknowledgements use the next offset after the last processed record.

| Behavior | `FILE` (default) | `IN_MEMORY` |
| --- | --- | --- |
| Engine | Chronicle Queue 2026.6 with bounded group commit | Immutable chunks published with CAS |
| Successful produce | Touched queue and metadata files have completed the force boundary | Complete batch published in memory |
| Restart | Topics and records recover from disk | Topics and records are lost |
| Consumer join | Starts at offset zero | Starts at the current partition end |
| Reclamation | File retention is not implemented | Slowest registered member's progress; no members allows immediate reclamation |
| Primary admission | 32 MiB / 1,024 outstanding writes per partition | Shared 256 MiB accounted retained-log budget |

In memory mode, **join before producing records a member needs**. Acknowledgement, leaving, and member expiry can release retained prefixes. Optional timed expiry is lossy and disabled by default. Buffer budgets are separate from retained-log admission and do not cap total process memory.

A failed or lost produce reply has an uncertain outcome; retrying can duplicate records. Read the [storage guide](setu-storage/README.md) for force semantics, recovery, ownership, admission, and [legacy migration](setu-storage/README.md#migrating-existing-data).

## Security

`SecureServerFactory` composes JSSE TLS/mTLS, DNS SAN client identity, default-deny exact ACL grants, and per-principal request/byte quotas. TLS clients verify the broker hostname. Security policies are configured by the embedding application.

Use the [secure server example](setu-server/README.md#secure-broker-wiring-example) and [security reference](setu-security/README.md) to configure this path. Development certificate generators are available for [PowerShell](scripts/generate-certs.ps1) and [Bash](scripts/generate-certs.sh); generated keys and stores stay outside version control. Selector transport supports plaintext only.

## Configuration

The standalone launcher accepts `--config <properties-file>`. Unknown properties fail startup; paths are relative to the launch working directory.

| Setting | Default | Purpose |
| --- | --- | --- |
| `storage.mode` | `FILE` | `FILE` or `IN_MEMORY` |
| `transport` | `blocking` | Virtual thread per connection, or plaintext `selector` |
| `record.max.bytes` | 1 MiB | Maximum payload per record |
| `queue.block.bytes` | 64 MiB | Chronicle mapping block size; does not limit file growth |
| `memory.log.max.bytes` | 256 MiB | Shared accounted retained-log budget |
| `requests.max.bytes` / `responses.max.bytes` | 128 MiB each | Shared transport buffer budgets |
| `ack.timeout.millis` / `consumer.timeout.millis` | 30,000 / 60,000 | Consumer progress and inactivity deadlines |

See the [complete configuration reference](docs/configuration.md) for all 17 properties, units, validation, and runtime options. The checked-in [server.properties](config/server.properties) is the local-development baseline.

## Build and verification

Run production-module and real TCP/mTLS integration tests:

```powershell
.\mvnw.cmd -pl setu-integration-tests -am test
```

On Linux/macOS, substitute `bash ./mvnw`. Python 3.10+ is required for the benchmark tooling and its tests:

```bash
python -m unittest discover -s scripts -p 'test_*.py'
```

Benchmark-helper tests, bounded chaos, load checks, baseline/target measurements, and AOT validation are covered by [CI](.github/workflows/ci.yml). The root Maven reactor includes benchmark workloads; use the commands above for ordinary functional verification. [Contributing](CONTRIBUTING.md) documents the broader verification matrix and repository conventions.

## Repository guide

| Path | Responsibility / documentation |
| --- | --- |
| [setu-core/](setu-core/README.md) | Broker API, topics, partitions, membership, and protocol codec |
| [setu-storage/](setu-storage/README.md) | Partition logs, group commit, recovery, and migration |
| [setu-server/](setu-server/README.md) | TCP transports, secure dispatch, standalone launcher, and AOT |
| [setu-client/](setu-client/README.md) | Synchronous Java client, plaintext and TLS |
| [setu-security/](setu-security/README.md) | Authentication, exact ACLs, certificates, and quotas |
| [setu-integration-tests/](setu-integration-tests/README.md) | Functional verification over real sockets |
| [setu-benchmark/](setu-benchmark/README.md) | Throughput, latency, JFR, crash recovery, and chaos |
| [docs/](docs/README.md) | Architecture, configuration, and operations guides |
| [scripts/](scripts/README.md) | Launch, certificate, profiling, reporting, and diagnostic tools |
| [examples/SetuQuickstart.java](examples/SetuQuickstart.java) | Runnable produce/fetch/acknowledge example |
| [config/](config/server.properties) | Server properties, JVM flags, and Logback configuration |
| [benchmark-badges/](benchmark-badges/node-target-results.json) | CI-maintained SVGs and measurement snapshots |

Generated builds, IDE metadata, runtime data, credentials, logs, and recordings are ignored. Published benchmark snapshots remain tracked because CI uses them to update the README.

## Implementation boundaries

- One broker process; no replication, failover, or distributed coordination.
- Consumer memberships and committed offsets are lost on restart; applications manage resumption.
- No automatic partition assignment/rebalancing, producer deduplication, or exactly-once delivery.
- File retention, payload checksums, and configurable acknowledgement modes are not implemented.
- The Java client serializes calls and has no automatic retry, reconnect, routing, or async API.
- ACL grants and quotas are process-local; persistent policy administration and automatic certificate rotation are not implemented.

## Performance

Reference targets are **500,000 records/s in memory** and **250,000 records/s durable**, with 1 KiB records. These are qualification goals. A completed run must also satisfy consumption, latency, CPU, heap, and error criteria. A smoke run or an unqualified result does not establish sustained capacity.

The [benchmark guide](setu-benchmark/README.md) documents reproducible profiles, strict/report-only policies, host budgets, and JFR interpretation. It also records a local Windows Chronicle diagnostic of approximately 55K durable records/s, which did not qualify. Results from the former custom storage engine do not qualify Chronicle Queue.

The generated blocks below retain their source revision, host information, and qualification status. Read them against that revision rather than as measurements of the current checkout; CI refreshes them after new runs.

<!-- benchmark-metrics-badges:start -->
[![in-memory: 500.00K/s target, 322.78K/s CI, 65% unqualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39317780845)
[![file: 250.00K/s target, 126.34K/s CI, 51% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39317780845)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest recorded target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 10737.42 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39317780845)
[![memory resources: 100% CPU, 256 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39317780845)
[![memory JFR: 1.06 GiB/s alloc, 30.4 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39317780845)
[![file p99: 14705.23 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39317780845)
[![file resources: 98% CPU, 124 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39317780845)
[![file JFR: 0.55 GiB/s alloc, 14.4 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39317780845)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `c88bb3b4ceaa4d2dbb8a6a7f20818f9baca652b0`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39317780845).

Runner: AMD EPYC 7763 64-Core Processor; 1.6 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

Runner: AMD EPYC 7763 64-Core Processor; 1.6 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

| Mode | Target/s | Median measured/s | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 322,782 | 10737.418 | 256 | 99.5810 | False |
| FILE | 250,000 | 126,345 | 14705.230 | 124 | 97.8750 | False |

| Mode | Repeat | Load | Measured/s | Consumed/s | p99 ms | CPU % | Heap slope MiB/s | Healthy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 124,996 | 125,013 | 93.848 | 95.5148 | 0.236 | False |
| FILE | 1 | 80% | 124,902 | 124,911 | 11156.849 | 88.9938 | 0.054 | False |
| FILE | 1 | 100% | 126,293 | 126,293 | 14688.453 | 88.7450 | 0.127 | False |
| FILE | 2 | 50% | 124,988 | 124,996 | 284.164 | 97.8750 | 0.187 | False |
| FILE | 2 | 80% | 126,379 | 126,396 | 10930.356 | 89.4164 | 0.092 | False |
| FILE | 2 | 100% | 126,421 | 126,438 | 14696.841 | 87.7498 | 0.019 | False |
| FILE | 3 | 50% | 124,996 | 125,022 | 65.929 | 97.1220 | 0.244 | False |
| FILE | 3 | 80% | 126,370 | 126,387 | 10947.133 | 89.4551 | -0.001 | False |
| FILE | 3 | 100% | 126,345 | 126,336 | 14705.230 | 89.3529 | -0.044 | False |
| IN_MEMORY | 1 | 50% | 249,993 | 251,017 | 140.509 | 88.1433 | 0.004 | False |
| IN_MEMORY | 1 | 80% | 275,951 | 275,951 | 9261.023 | 99.4574 | -0.133 | False |
| IN_MEMORY | 1 | 100% | 322,782 | 322,773 | 10519.314 | 99.5584 | 0.206 | False |
| IN_MEMORY | 2 | 50% | 249,993 | 251,025 | 223.871 | 89.0396 | -0.489 | False |
| IN_MEMORY | 2 | 80% | 278,605 | 278,613 | 9000.976 | 99.4134 | -0.132 | False |
| IN_MEMORY | 2 | 100% | 320,034 | 320,043 | 10737.418 | 99.4998 | 0.122 | False |
| IN_MEMORY | 3 | 50% | 249,993 | 251,034 | 151.781 | 88.4557 | -0.304 | False |
| IN_MEMORY | 3 | 80% | 319,932 | 320,017 | 5968.495 | 99.5624 | 0.132 | False |
| IN_MEMORY | 3 | 100% | 327,492 | 327,270 | 10259.268 | 99.5810 | 0.146 | False |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | p99 93.848 ms outside [0,10) ms; CPU 95.5148% outside 0–85% of allocated budget |
| FILE | 1 | 80% | throughput 124,902/s <95% of offered 200,000/s; p99 11156.849 ms outside [0,100) ms; CPU 88.9938% outside 0–85% of allocated budget |
| FILE | 1 | 100% | throughput 126,293/s <95% of offered 250,000/s; p99 14688.453 ms outside [0,100) ms; CPU 88.7450% outside 0–85% of allocated budget |
| FILE | 2 | 50% | p99 284.164 ms outside [0,10) ms; CPU 97.8750% outside 0–85% of allocated budget |
| FILE | 2 | 80% | throughput 126,379/s <95% of offered 200,000/s; p99 10930.356 ms outside [0,100) ms; CPU 89.4164% outside 0–85% of allocated budget |
| FILE | 2 | 100% | throughput 126,421/s <95% of offered 250,000/s; p99 14696.841 ms outside [0,100) ms; CPU 87.7498% outside 0–85% of allocated budget |
| FILE | 3 | 50% | p99 65.929 ms outside [0,10) ms; CPU 97.1220% outside 0–85% of allocated budget |
| FILE | 3 | 80% | throughput 126,370/s <95% of offered 200,000/s; p99 10947.133 ms outside [0,100) ms; CPU 89.4551% outside 0–85% of allocated budget |
| FILE | 3 | 100% | throughput 126,345/s <95% of offered 250,000/s; p99 14705.230 ms outside [0,100) ms; CPU 89.3529% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 50% | p99 140.509 ms outside [0,5) ms; CPU 88.1433% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 80% | throughput 275,951/s <95% of offered 400,000/s; p99 9261.023 ms outside [0,50) ms; CPU 99.4574% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 100% | throughput 322,782/s <95% of offered 500,000/s; p99 10519.314 ms outside [0,50) ms; CPU 99.5584% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 50% | p99 223.871 ms outside [0,5) ms; CPU 89.0396% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 80% | throughput 278,605/s <95% of offered 400,000/s; p99 9000.976 ms outside [0,50) ms; CPU 99.4134% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 100% | throughput 320,034/s <95% of offered 500,000/s; p99 10737.418 ms outside [0,50) ms; CPU 99.4998% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 50% | p99 151.781 ms outside [0,5) ms; CPU 88.4557% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 80% | throughput 319,932/s <95% of offered 400,000/s; p99 5968.495 ms outside [0,50) ms; CPU 99.5624% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 100% | throughput 327,492/s <95% of offered 500,000/s; p99 10259.268 ms outside [0,50) ms; CPU 99.5810% outside 0–85% of allocated budget |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 9.118 | 2.154 | 2.288 | 93.848 |
| FILE | 1 | 80% | 33.423 | 2.277 | 2.214 | 11156.849 |
| FILE | 1 | 100% | 33.456 | 2.173 | 1.823 | 14688.453 |
| FILE | 2 | 50% | 12.575 | 2.037 | 2.236 | 284.164 |
| FILE | 2 | 80% | 33.784 | 2.443 | 2.154 | 10930.356 |
| FILE | 2 | 100% | 33.849 | 2.310 | 1.940 | 14696.841 |
| FILE | 3 | 50% | 8.536 | 1.849 | 1.860 | 65.929 |
| FILE | 3 | 80% | 33.096 | 2.472 | 2.187 | 10947.133 |
| FILE | 3 | 100% | 34.505 | 1.992 | 1.716 | 14705.230 |
| IN_MEMORY | 1 | 50% | 1.613 | 0.690 | 0.582 | 140.509 |
| IN_MEMORY | 1 | 80% | 5.698 | 2.757 | 2.101 | 9261.023 |
| IN_MEMORY | 1 | 100% | 5.054 | 2.951 | 2.089 | 10519.314 |
| IN_MEMORY | 2 | 50% | 2.298 | 0.818 | 0.793 | 223.871 |
| IN_MEMORY | 2 | 80% | 5.284 | 2.623 | 2.010 | 9000.976 |
| IN_MEMORY | 2 | 100% | 5.255 | 2.951 | 2.052 | 10737.418 |
| IN_MEMORY | 3 | 50% | 2.177 | 0.828 | 0.752 | 151.781 |
| IN_MEMORY | 3 | 80% | 5.337 | 3.060 | 2.097 | 5968.495 |
| IN_MEMORY | 3 | 100% | 5.218 | 2.836 | 1.985 | 10259.268 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 14667 | 19937.422965 | 5.484543 | 29.467025 |
| FILE | 1 | 2 | 14641 | 40198.511135 | 32.243711 | 40.075328 |
| FILE | 1 | 3 | 14804 | 39702.930577 | 32.112639 | 41.551767 |
| FILE | 2 | 1 | 14657 | 18247.784412 | 5.992447 | 30.593247 |
| FILE | 2 | 2 | 14813 | 37839.087994 | 31.031295 | 63.244333 |
| FILE | 2 | 3 | 14818 | 38223.172015 | 31.768575 | 42.009234 |
| FILE | 3 | 1 | 14665 | 17087.971499 | 4.947967 | 24.617276 |
| FILE | 3 | 2 | 14813 | 37057.168970 | 29.278207 | 40.490611 |
| FILE | 3 | 3 | 14809 | 40117.015139 | 32.899071 | 41.925791 |
| IN_MEMORY | 1 | 1 | 0 | 0.000000 | 0.000000 | 0.000000 |
| IN_MEMORY | 1 | 2 | 0 | 0.000000 | 0.000000 | 0.000000 |
| IN_MEMORY | 1 | 3 | 0 | 0.000000 | 0.000000 | 0.000000 |
| IN_MEMORY | 2 | 1 | 0 | 0.000000 | 0.000000 | 0.000000 |
| IN_MEMORY | 2 | 2 | 0 | 0.000000 | 0.000000 | 0.000000 |
| IN_MEMORY | 2 | 3 | 0 | 0.000000 | 0.000000 | 0.000000 |
| IN_MEMORY | 3 | 1 | 0 | 0.000000 | 0.000000 | 0.000000 |
| IN_MEMORY | 3 | 2 | 0 | 0.000000 | 0.000000 | 0.000000 |
| IN_MEMORY | 3 | 3 | 0 | 0.000000 | 0.000000 | 0.000000 |

Waits are elapsed time inside jdk.FileForce, clipped to each measured stage. Concurrent calls are summed separately, so total wait can exceed stage duration. New recordings capture every force; older recordings may omit fast calls. Unavailable metrics indicate an older summary, rather than zero waits.
<!-- benchmark-capacity-details:end -->

</details>
