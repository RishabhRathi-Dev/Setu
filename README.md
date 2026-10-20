# Setu

**A single-node Java message broker with partitioned logs and durable acknowledgements.**

[![CI](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml/badge.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![Java 26](https://img.shields.io/badge/Java-26-2563eb)](pom.xml)
[![Maven Wrapper](https://img.shields.io/badge/build-Maven_Wrapper-2563eb)](.mvn/wrapper/maven-wrapper.properties)

[Quick start](#quick-start) · [Documentation](docs/README.md) · [Examples](examples/README.md) · [Contributing](CONTRIBUTING.md) · [Get help](SUPPORT.md)

Setu stores ordered records in topic partitions and exposes explicit produce, fetch, and consumer-progress operations. The default `FILE` engine uses OpenHFT Chronicle Queue; `IN_MEMORY` provides bounded, volatile storage. The implementation includes blocking and selector TCP transports, a synchronous Java client, and an embedded mTLS security pipeline.

Setu is under active development. It runs as one broker process, with no replication or failover. Consumer state and security policies are process-local. See [implementation boundaries](#implementation-boundaries) before choosing a deployment model.

## At a glance

- **Partitioned messaging:** explicit partition selection, ordered offsets, batch produce, and long-poll fetch.
- **Two storage modes:** durable daily Chronicle logs with group commit and optional file retention, or bounded memory with consumer-driven reclamation.
- **Java integration:** a synchronous TCP client, runnable examples, and a broker that can be embedded directly.
- **Embedded security:** mTLS identities, default-deny exact ACLs, and per-principal request/byte quotas.
- **Measured behavior:** functional TCP/mTLS tests, recovery and chaos checks, and benchmark reports that retain qualification failures.

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
- [Help and contributions](#help-and-contributions)

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

To use Setu in an application, [install the client modules locally and add the Maven dependency](setu-client/README.md#add-to-a-maven-application). The client guide also documents error handling, TLS, timeouts, and ownership.

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
| Reclamation | Optional age-based retention of completed daily files | Slowest registered member's progress; no members allows immediate reclamation |
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
| `file.retention.millis` | `0` | Expire completed daily files by age; zero disables retention |
| `memory.log.max.bytes` | 256 MiB | Shared accounted retained-log budget |
| `requests.max.bytes` / `responses.max.bytes` | 128 MiB each | Shared transport buffer budgets |
| `ack.timeout.millis` / `consumer.timeout.millis` | 30,000 / 60,000 | Consumer progress and inactivity deadlines |

See the [complete configuration reference](docs/configuration.md) for all properties, units, validation, and runtime options, including [file retention examples](docs/configuration.md#file-retention-examples). The checked-in [server.properties](config/server.properties) is the local-development baseline.

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
| [AGENTS.md](AGENTS.md) | Repository knowledge base, source entry points, invariants, and agent verification guidance |
| [scripts/](scripts/README.md) | Launch, certificate, profiling, reporting, and diagnostic tools |
| [examples/](examples/README.md) | Runnable clients, batch/partition fetches, long polling, embedded brokers, recovery and retention |
| [config/](config/server.properties) | Server properties, JVM flags, and Logback configuration |
| [benchmark-badges/](benchmark-badges/node-target-results.json) | CI-maintained SVGs and measurement snapshots |

Generated builds, IDE metadata, runtime data, credentials, logs, and recordings are ignored. Published benchmark snapshots remain tracked because CI uses them to update the README.

## Implementation boundaries

- One broker process; no replication, failover, or distributed coordination.
- Consumer memberships and committed offsets are lost on restart; applications manage resumption.
- No automatic partition assignment/rebalancing, producer deduplication, or exactly-once delivery.
- Payload checksums and configurable acknowledgement modes are not implemented.
- The Java client serializes calls and has no automatic retry, reconnect, routing, or async API.
- ACL grants and quotas are process-local; persistent policy administration and automatic certificate rotation are not implemented.

## Performance

Reference targets are **500,000 records/s in memory** and **250,000 records/s durable**, with 1 KiB records. These are qualification goals. A completed run must also satisfy consumption, latency, CPU, heap, and error criteria. A smoke run or an unqualified result does not establish sustained capacity.

The [benchmark guide](setu-benchmark/README.md) documents reproducible profiles, strict/report-only policies, host budgets, and JFR interpretation. It also records a local Windows Chronicle diagnostic of approximately 55K durable records/s, which did not qualify. Results from the former custom storage engine do not qualify Chronicle Queue.

The generated blocks below retain their source revision, host information, and qualification status. Read them against that revision rather than as measurements of the current checkout; CI refreshes them after new runs.

<!-- benchmark-metrics-badges:start -->
[![in-memory: 500.00K/s target, 307.92K/s CI, 62% unqualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39417009195)
[![file: 250.00K/s target, 72.96K/s CI, 29% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39417009195)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest recorded target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 12104.76 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39417009195)
[![memory resources: 100% CPU, 256 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39417009195)
[![memory JFR: 1.02 GiB/s alloc, 31.4 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39417009195)
[![file p99: 21189.62 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39417009195)
[![file resources: 63% CPU, 126 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39417009195)
[![file JFR: 0.35 GiB/s alloc, 5.2 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39417009195)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `3aac5c94604957a925bd6eeae89456023a9f21bc`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39417009195).

Runner: INTEL(R) XEON(R) PLATINUM 8573C; 1.6 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

Runner: AMD EPYC 7763 64-Core Processor; 1.6 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

| Mode | Target/s | Median measured/s | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 307,917 | 12104.761 | 256 | 99.5776 | False |
| FILE | 250,000 | 72,960 | 21189.624 | 126 | 63.4357 | False |

| Mode | Repeat | Load | Measured/s | Consumed/s | p99 ms | CPU % | Heap slope MiB/s | Healthy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 63,497 | 63,522 | 14629.732 | 58.0385 | 0.167 | False |
| FILE | 1 | 80% | 71,492 | 71,509 | 19327.353 | 58.4993 | 0.154 | False |
| FILE | 1 | 100% | 72,960 | 72,986 | 21038.629 | 60.7693 | 0.004 | False |
| FILE | 2 | 50% | 72,320 | 72,346 | 12532.580 | 60.9972 | -0.004 | False |
| FILE | 2 | 80% | 72,252 | 72,269 | 18975.031 | 60.2059 | 0.054 | False |
| FILE | 2 | 100% | 71,825 | 71,842 | 21189.624 | 60.3518 | 0.150 | False |
| FILE | 3 | 50% | 72,371 | 72,380 | 12524.192 | 63.4357 | 0.041 | False |
| FILE | 3 | 80% | 72,892 | 72,909 | 18891.145 | 61.2498 | 0.127 | False |
| FILE | 3 | 100% | 73,020 | 73,037 | 21038.629 | 58.6643 | 0.171 | False |
| IN_MEMORY | 1 | 50% | 249,993 | 250,650 | 7.438 | 85.0604 | 0.372 | False |
| IN_MEMORY | 1 | 80% | 319,821 | 319,565 | 5909.774 | 99.5776 | 0.012 | False |
| IN_MEMORY | 1 | 100% | 307,917 | 307,925 | 11408.507 | 99.4164 | 0.002 | False |
| IN_MEMORY | 2 | 50% | 249,993 | 250,052 | 1.579 | 85.9974 | 0.150 | False |
| IN_MEMORY | 2 | 80% | 299,546 | 299,580 | 7482.638 | 99.3936 | -0.206 | False |
| IN_MEMORY | 2 | 100% | 308,446 | 308,429 | 11450.450 | 99.3090 | -0.066 | False |
| IN_MEMORY | 3 | 50% | 249,993 | 251,042 | 220.463 | 87.5806 | -0.266 | False |
| IN_MEMORY | 3 | 80% | 292,659 | 292,651 | 7931.429 | 99.3957 | 0.185 | False |
| IN_MEMORY | 3 | 100% | 296,414 | 296,414 | 12104.761 | 99.4544 | -0.117 | False |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | throughput 63,497/s <95% of offered 125,000/s; p99 14629.732 ms outside [0,10) ms |
| FILE | 1 | 80% | throughput 71,492/s <95% of offered 200,000/s; p99 19327.353 ms outside [0,100) ms |
| FILE | 1 | 100% | throughput 72,960/s <95% of offered 250,000/s; p99 21038.629 ms outside [0,100) ms |
| FILE | 2 | 50% | throughput 72,320/s <95% of offered 125,000/s; p99 12532.580 ms outside [0,10) ms |
| FILE | 2 | 80% | throughput 72,252/s <95% of offered 200,000/s; p99 18975.031 ms outside [0,100) ms |
| FILE | 2 | 100% | throughput 71,825/s <95% of offered 250,000/s; p99 21189.624 ms outside [0,100) ms |
| FILE | 3 | 50% | throughput 72,371/s <95% of offered 125,000/s; p99 12524.192 ms outside [0,10) ms |
| FILE | 3 | 80% | throughput 72,892/s <95% of offered 200,000/s; p99 18891.145 ms outside [0,100) ms |
| FILE | 3 | 100% | throughput 73,020/s <95% of offered 250,000/s; p99 21038.629 ms outside [0,100) ms |
| IN_MEMORY | 1 | 50% | p99 7.438 ms outside [0,5) ms; CPU 85.0604% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 80% | throughput 319,821/s <95% of offered 400,000/s; p99 5909.774 ms outside [0,50) ms; CPU 99.5776% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 100% | throughput 307,917/s <95% of offered 500,000/s; p99 11408.507 ms outside [0,50) ms; CPU 99.4164% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 50% | CPU 85.9974% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 80% | throughput 299,546/s <95% of offered 400,000/s; p99 7482.638 ms outside [0,50) ms; CPU 99.3936% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 100% | throughput 308,446/s <95% of offered 500,000/s; p99 11450.450 ms outside [0,50) ms; CPU 99.3090% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 50% | p99 220.463 ms outside [0,5) ms; CPU 87.5806% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 80% | throughput 292,659/s <95% of offered 400,000/s; p99 7931.429 ms outside [0,50) ms; CPU 99.3957% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 100% | throughput 296,414/s <95% of offered 500,000/s; p99 12104.761 ms outside [0,50) ms; CPU 99.4544% outside 0–85% of allocated budget |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 11.821 | 1.090 | 1.637 | 14629.732 |
| FILE | 1 | 80% | 11.035 | 1.202 | 1.367 | 19327.353 |
| FILE | 1 | 100% | 10.502 | 1.105 | 1.152 | 21038.629 |
| FILE | 2 | 50% | 10.650 | 1.166 | 1.799 | 12532.580 |
| FILE | 2 | 80% | 10.969 | 1.176 | 1.404 | 18975.031 |
| FILE | 2 | 100% | 11.108 | 1.125 | 1.140 | 21189.624 |
| FILE | 3 | 50% | 10.846 | 1.104 | 1.482 | 12524.192 |
| FILE | 3 | 80% | 10.699 | 1.033 | 1.313 | 18891.145 |
| FILE | 3 | 100% | 10.691 | 1.020 | 1.324 | 21038.629 |
| IN_MEMORY | 1 | 50% | 0.824 | 0.561 | 0.458 | 7.438 |
| IN_MEMORY | 1 | 80% | 5.423 | 2.894 | 2.103 | 5909.774 |
| IN_MEMORY | 1 | 100% | 4.563 | 2.193 | 1.978 | 11408.507 |
| IN_MEMORY | 2 | 50% | 0.733 | 0.467 | 0.384 | 1.579 |
| IN_MEMORY | 2 | 80% | 4.813 | 2.410 | 2.018 | 7482.638 |
| IN_MEMORY | 2 | 100% | 4.350 | 2.249 | 1.918 | 11450.450 |
| IN_MEMORY | 3 | 50% | 1.751 | 0.670 | 0.650 | 220.463 |
| IN_MEMORY | 3 | 80% | 5.145 | 2.505 | 2.103 | 7931.429 |
| IN_MEMORY | 3 | 100% | 4.735 | 2.329 | 1.915 | 12104.761 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 7448 | 53167.195589 | 10.625023 | 402.504828 |
| FILE | 1 | 2 | 8382 | 52591.555627 | 9.904127 | 27.150654 |
| FILE | 1 | 3 | 8555 | 52402.442392 | 9.388031 | 20.172748 |
| FILE | 2 | 1 | 8483 | 51807.899927 | 9.355263 | 22.957058 |
| FILE | 2 | 2 | 8471 | 52404.557112 | 9.609215 | 19.241661 |
| FILE | 2 | 3 | 8421 | 52349.007322 | 9.863167 | 16.851894 |
| FILE | 3 | 1 | 8488 | 52145.592860 | 9.527295 | 21.536326 |
| FILE | 3 | 2 | 8546 | 52686.235678 | 9.551871 | 17.596729 |
| FILE | 3 | 3 | 8561 | 53025.108468 | 9.592831 | 15.219832 |
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

## Help and contributions

For setup help or a reproducible bug, start with [support and troubleshooting](SUPPORT.md). Use the [issue forms](https://github.com/RishabhRathi-Dev/Setu/issues/new/choose) for bugs and feature requests. Report vulnerabilities privately following the [security policy](SECURITY.md).

Contributions should include focused changes, relevant verification, and updated documentation. The [contributing guide](CONTRIBUTING.md) covers setup, checks, and pull requests; [AGENTS.md](AGENTS.md) records the repository map and contracts for coding agents.
