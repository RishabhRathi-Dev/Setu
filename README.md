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
[![in-memory: 500.00K/s target, 317.93K/s CI, 64% unqualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39396765860)
[![file: 250.00K/s target, 126.28K/s CI, 51% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39396765860)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest recorded target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 12775.85 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39396765860)
[![memory resources: 100% CPU, 256 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39396765860)
[![memory JFR: 1.05 GiB/s alloc, 30.5 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39396765860)
[![file p99: 14738.78 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39396765860)
[![file resources: 98% CPU, 122 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39396765860)
[![file JFR: 0.55 GiB/s alloc, 14.9 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39396765860)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `3e20ca03c6d07db6100c2bf3fe1355f8950394e9`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39396765860).

Runner: AMD EPYC 7763 64-Core Processor; 1.6 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

Runner: AMD EPYC 7763 64-Core Processor; 1.6 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

| Mode | Target/s | Median measured/s | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 317,935 | 12775.850 | 256 | 99.5386 | False |
| FILE | 250,000 | 126,285 | 14738.784 | 122 | 97.8290 | False |

| Mode | Repeat | Load | Measured/s | Consumed/s | p99 ms | CPU % | Heap slope MiB/s | Healthy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 124,996 | 125,005 | 50.528 | 96.5095 | 0.121 | False |
| FILE | 1 | 80% | 126,251 | 126,251 | 10947.133 | 90.7681 | 0.153 | False |
| FILE | 1 | 100% | 126,293 | 126,310 | 14705.230 | 88.2895 | 0.104 | False |
| FILE | 2 | 50% | 124,979 | 124,979 | 80.019 | 97.3102 | 0.193 | False |
| FILE | 2 | 80% | 126,396 | 126,404 | 11223.958 | 90.4353 | 0.064 | False |
| FILE | 2 | 100% | 126,071 | 126,071 | 14738.784 | 88.4372 | 0.097 | False |
| FILE | 3 | 50% | 124,996 | 125,013 | 105.972 | 97.8290 | 0.162 | False |
| FILE | 3 | 80% | 126,353 | 126,362 | 11223.958 | 92.7467 | 0.014 | False |
| FILE | 3 | 100% | 126,285 | 126,302 | 14730.396 | 89.3307 | 0.067 | False |
| IN_MEMORY | 1 | 50% | 249,993 | 251,034 | 154.141 | 89.0564 | -0.052 | False |
| IN_MEMORY | 1 | 80% | 268,740 | 268,740 | 9789.506 | 98.8683 | 0.025 | False |
| IN_MEMORY | 1 | 100% | 317,935 | 317,824 | 10821.304 | 99.5386 | -0.069 | False |
| IN_MEMORY | 2 | 50% | 249,993 | 251,042 | 272.892 | 90.6841 | -0.494 | False |
| IN_MEMORY | 2 | 80% | 273,510 | 273,502 | 9344.909 | 99.3538 | -0.018 | False |
| IN_MEMORY | 2 | 100% | 318,763 | 318,754 | 10770.973 | 99.5379 | -0.051 | False |
| IN_MEMORY | 3 | 50% | 249,993 | 251,034 | 167.641 | 89.9353 | -0.835 | False |
| IN_MEMORY | 3 | 80% | 264,747 | 264,747 | 10049.552 | 99.4338 | 0.096 | False |
| IN_MEMORY | 3 | 100% | 284,766 | 284,740 | 12775.850 | 98.8101 | -0.163 | False |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | p99 50.528 ms outside [0,10) ms; CPU 96.5095% outside 0–85% of allocated budget |
| FILE | 1 | 80% | throughput 126,251/s <95% of offered 200,000/s; p99 10947.133 ms outside [0,100) ms; CPU 90.7681% outside 0–85% of allocated budget |
| FILE | 1 | 100% | throughput 126,293/s <95% of offered 250,000/s; p99 14705.230 ms outside [0,100) ms; CPU 88.2895% outside 0–85% of allocated budget |
| FILE | 2 | 50% | p99 80.019 ms outside [0,10) ms; CPU 97.3102% outside 0–85% of allocated budget |
| FILE | 2 | 80% | throughput 126,396/s <95% of offered 200,000/s; p99 11223.958 ms outside [0,100) ms; CPU 90.4353% outside 0–85% of allocated budget |
| FILE | 2 | 100% | throughput 126,071/s <95% of offered 250,000/s; p99 14738.784 ms outside [0,100) ms; CPU 88.4372% outside 0–85% of allocated budget |
| FILE | 3 | 50% | p99 105.972 ms outside [0,10) ms; CPU 97.8290% outside 0–85% of allocated budget |
| FILE | 3 | 80% | throughput 126,353/s <95% of offered 200,000/s; p99 11223.958 ms outside [0,100) ms; CPU 92.7467% outside 0–85% of allocated budget |
| FILE | 3 | 100% | throughput 126,285/s <95% of offered 250,000/s; p99 14730.396 ms outside [0,100) ms; CPU 89.3307% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 50% | p99 154.141 ms outside [0,5) ms; CPU 89.0564% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 80% | throughput 268,740/s <95% of offered 400,000/s; p99 9789.506 ms outside [0,50) ms; CPU 98.8683% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 100% | throughput 317,935/s <95% of offered 500,000/s; p99 10821.304 ms outside [0,50) ms; CPU 99.5386% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 50% | p99 272.892 ms outside [0,5) ms; CPU 90.6841% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 80% | throughput 273,510/s <95% of offered 400,000/s; p99 9344.909 ms outside [0,50) ms; CPU 99.3538% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 100% | throughput 318,763/s <95% of offered 500,000/s; p99 10770.973 ms outside [0,50) ms; CPU 99.5379% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 50% | p99 167.641 ms outside [0,5) ms; CPU 89.9353% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 80% | throughput 264,747/s <95% of offered 400,000/s; p99 10049.552 ms outside [0,50) ms; CPU 99.4338% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 100% | throughput 284,766/s <95% of offered 500,000/s; p99 12775.850 ms outside [0,50) ms; CPU 98.8101% outside 0–85% of allocated budget |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 11.485 | 1.774 | 1.950 | 50.528 |
| FILE | 1 | 80% | 32.899 | 2.089 | 1.924 | 10947.133 |
| FILE | 1 | 100% | 33.784 | 1.994 | 1.780 | 14705.230 |
| FILE | 2 | 50% | 7.737 | 1.809 | 1.902 | 80.019 |
| FILE | 2 | 80% | 32.522 | 2.337 | 2.074 | 11223.958 |
| FILE | 2 | 100% | 32.702 | 2.163 | 1.592 | 14738.784 |
| FILE | 3 | 50% | 9.085 | 2.169 | 2.086 | 105.972 |
| FILE | 3 | 80% | 29.999 | 2.359 | 2.173 | 11223.958 |
| FILE | 3 | 100% | 33.309 | 2.136 | 1.541 | 14730.396 |
| IN_MEMORY | 1 | 50% | 1.711 | 0.734 | 0.600 | 154.141 |
| IN_MEMORY | 1 | 80% | 5.968 | 2.740 | 2.073 | 9789.506 |
| IN_MEMORY | 1 | 100% | 4.858 | 2.728 | 2.076 | 10821.304 |
| IN_MEMORY | 2 | 50% | 2.419 | 0.893 | 0.880 | 272.892 |
| IN_MEMORY | 2 | 80% | 5.460 | 2.718 | 2.200 | 9344.909 |
| IN_MEMORY | 2 | 100% | 5.231 | 2.816 | 2.095 | 10770.973 |
| IN_MEMORY | 3 | 50% | 2.071 | 0.804 | 0.693 | 167.641 |
| IN_MEMORY | 3 | 80% | 5.964 | 2.877 | 2.177 | 10049.552 |
| IN_MEMORY | 3 | 100% | 5.009 | 2.402 | 1.976 | 12775.850 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 14666 | 19964.068701 | 8.404991 | 27.749365 |
| FILE | 1 | 2 | 14798 | 39801.857083 | 31.080447 | 42.587135 |
| FILE | 1 | 3 | 14804 | 40200.287048 | 32.456703 | 40.972132 |
| FILE | 2 | 1 | 14663 | 17996.034324 | 4.894719 | 25.951994 |
| FILE | 2 | 2 | 14815 | 39887.437674 | 30.507007 | 41.553159 |
| FILE | 2 | 3 | 14778 | 39335.016691 | 31.064063 | 39.827690 |
| FILE | 3 | 1 | 14659 | 18815.420831 | 5.541887 | 57.002268 |
| FILE | 3 | 2 | 14813 | 38556.152248 | 27.983871 | 40.842788 |
| FILE | 3 | 3 | 14803 | 39349.128104 | 31.506431 | 39.761322 |
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
