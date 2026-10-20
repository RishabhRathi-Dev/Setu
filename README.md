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
[![in-memory: 500.00K/s target, 499.99K/s CI, 100% unqualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39411390303)
[![file: 250.00K/s target, 127.01K/s CI, 51% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39411390303)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest recorded target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 4238.34 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39411390303)
[![memory resources: 100% CPU, 256 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39411390303)
[![memory JFR: 1.65 GiB/s alloc, 34.6 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39411390303)
[![file p99: 14663.29 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39411390303)
[![file resources: 95% CPU, 124 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39411390303)
[![file JFR: 0.56 GiB/s alloc, 19.2 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39411390303)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `31f9b0fdf4ef3551e0479efbc4627219fb4c29c7`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39411390303).

Runner: AMD EPYC 9V74 80-Core Processor; 1.6 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

Runner: AMD EPYC 9V74 80-Core Processor; 1.6 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

| Mode | Target/s | Median measured/s | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 499,994 | 4238.344 | 256 | 99.5195 | False |
| FILE | 250,000 | 127,010 | 14663.287 | 124 | 94.6450 | False |

| Mode | Repeat | Load | Measured/s | Consumed/s | p99 ms | CPU % | Heap slope MiB/s | Healthy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 124,442 | 124,459 | 250.479 | 89.5589 | 0.093 | False |
| FILE | 1 | 80% | 125,833 | 125,850 | 11198.792 | 84.0399 | 0.150 | False |
| FILE | 1 | 100% | 126,643 | 126,660 | 14663.287 | 84.6016 | -0.039 | False |
| FILE | 2 | 50% | 124,996 | 125,005 | 105.644 | 94.6450 | 0.237 | False |
| FILE | 2 | 80% | 127,053 | 127,070 | 10838.082 | 84.1846 | 0.064 | False |
| FILE | 2 | 100% | 127,223 | 127,223 | 14587.789 | 84.3074 | 0.034 | False |
| FILE | 3 | 50% | 124,996 | 125,013 | 87.949 | 93.7666 | 0.202 | False |
| FILE | 3 | 80% | 127,010 | 127,019 | 10838.082 | 87.6017 | 0.172 | False |
| FILE | 3 | 100% | 127,010 | 127,019 | 14604.567 | 83.7463 | 0.058 | False |
| IN_MEMORY | 1 | 50% | 249,993 | 250,027 | 1.138 | 66.1223 | 0.100 | True |
| IN_MEMORY | 1 | 80% | 400,000 | 400,162 | 1.097 | 85.5809 | 0.339 | False |
| IN_MEMORY | 1 | 100% | 499,994 | 500,002 | 1.845 | 94.7689 | 0.154 | False |
| IN_MEMORY | 2 | 50% | 249,993 | 250,061 | 1.109 | 66.4158 | 0.155 | True |
| IN_MEMORY | 2 | 80% | 399,983 | 399,983 | 1.357 | 81.5803 | 0.238 | True |
| IN_MEMORY | 2 | 100% | 499,994 | 500,002 | 2.169 | 94.2056 | 0.193 | False |
| IN_MEMORY | 3 | 50% | 249,993 | 249,993 | 1.172 | 66.6231 | -0.024 | True |
| IN_MEMORY | 3 | 80% | 348,749 | 348,715 | 3823.108 | 99.4768 | 0.033 | False |
| IN_MEMORY | 3 | 100% | 428,595 | 428,476 | 4238.344 | 99.5195 | -0.010 | False |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | p99 250.479 ms outside [0,10) ms; CPU 89.5589% outside 0–85% of allocated budget |
| FILE | 1 | 80% | throughput 125,833/s <95% of offered 200,000/s; p99 11198.792 ms outside [0,100) ms |
| FILE | 1 | 100% | throughput 126,643/s <95% of offered 250,000/s; p99 14663.287 ms outside [0,100) ms |
| FILE | 2 | 50% | p99 105.644 ms outside [0,10) ms; CPU 94.6450% outside 0–85% of allocated budget |
| FILE | 2 | 80% | throughput 127,053/s <95% of offered 200,000/s; p99 10838.082 ms outside [0,100) ms |
| FILE | 2 | 100% | throughput 127,223/s <95% of offered 250,000/s; p99 14587.789 ms outside [0,100) ms |
| FILE | 3 | 50% | p99 87.949 ms outside [0,10) ms; CPU 93.7666% outside 0–85% of allocated budget |
| FILE | 3 | 80% | throughput 127,010/s <95% of offered 200,000/s; p99 10838.082 ms outside [0,100) ms; CPU 87.6017% outside 0–85% of allocated budget |
| FILE | 3 | 100% | throughput 127,010/s <95% of offered 250,000/s; p99 14604.567 ms outside [0,100) ms |
| IN_MEMORY | 1 | 80% | CPU 85.5809% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 100% | CPU 94.7689% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 100% | CPU 94.2056% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 80% | throughput 348,749/s <95% of offered 400,000/s; p99 3823.108 ms outside [0,50) ms; CPU 99.4768% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 100% | throughput 428,595/s <95% of offered 500,000/s; p99 4238.344 ms outside [0,50) ms; CPU 99.5195% outside 0–85% of allocated budget |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 24.494 | 2.140 | 2.251 | 250.479 |
| FILE | 1 | 80% | 34.996 | 2.183 | 2.183 | 11198.792 |
| FILE | 1 | 100% | 35.553 | 2.210 | 1.983 | 14663.287 |
| FILE | 2 | 50% | 7.565 | 1.873 | 2.037 | 105.644 |
| FILE | 2 | 80% | 34.472 | 2.458 | 2.245 | 10838.082 |
| FILE | 2 | 100% | 35.062 | 2.277 | 2.064 | 14587.789 |
| FILE | 3 | 50% | 6.894 | 1.841 | 2.243 | 87.949 |
| FILE | 3 | 80% | 33.489 | 2.611 | 2.478 | 10838.082 |
| FILE | 3 | 100% | 34.472 | 2.265 | 2.043 | 14604.567 |
| IN_MEMORY | 1 | 50% | 0.575 | 0.336 | 0.235 | 1.138 |
| IN_MEMORY | 1 | 80% | 0.502 | 0.358 | 0.227 | 1.097 |
| IN_MEMORY | 1 | 100% | 0.565 | 0.484 | 0.320 | 1.845 |
| IN_MEMORY | 2 | 50% | 0.581 | 0.358 | 0.255 | 1.109 |
| IN_MEMORY | 2 | 80% | 0.572 | 0.405 | 0.245 | 1.357 |
| IN_MEMORY | 2 | 100% | 0.621 | 0.506 | 0.335 | 2.169 |
| IN_MEMORY | 3 | 50% | 0.553 | 0.327 | 0.234 | 1.172 |
| IN_MEMORY | 3 | 80% | 4.219 | 2.292 | 1.895 | 3823.108 |
| IN_MEMORY | 3 | 100% | 4.231 | 2.492 | 1.644 | 4238.344 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 14592 | 30780.930769 | 23.101439 | 97.664860 |
| FILE | 1 | 2 | 14750 | 39789.326626 | 33.292287 | 41.759178 |
| FILE | 1 | 3 | 14845 | 37521.761275 | 32.522239 | 41.310424 |
| FILE | 2 | 1 | 14661 | 14610.830640 | 3.801087 | 25.496736 |
| FILE | 2 | 2 | 14893 | 37462.444419 | 30.064639 | 40.274987 |
| FILE | 2 | 3 | 14913 | 38152.025386 | 31.440895 | 42.440525 |
| FILE | 3 | 1 | 14665 | 14122.256601 | 3.788799 | 24.341221 |
| FILE | 3 | 2 | 14887 | 36474.078215 | 29.769727 | 41.504625 |
| FILE | 3 | 3 | 14888 | 38321.362946 | 32.194559 | 41.448030 |
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
