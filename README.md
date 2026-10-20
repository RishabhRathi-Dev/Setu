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
| `consumers.max` / `consumer.partitions.max` | 10,000 / 100,000 | Active member and summed partition membership admission |
| `socket.timeout.millis` / `handshake.timeout.millis` / `frame.timeout.millis` | 60,000 / 10,000 / 30,000 | Idle/read, absolute setup and whole-frame deadlines |
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

CI also runs Windows functional tests and [a source security gate](.github/workflows/security.yml). Benchmark-helper tests, bounded chaos, load checks, baseline/target measurements, and AOT validation are covered by [CI](.github/workflows/ci.yml). The root Maven reactor includes benchmark workloads; use the commands above for ordinary functional verification. [Contributing](CONTRIBUTING.md) documents the broader verification matrix and repository conventions.

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
- Consumer memberships and member acknowledgement progress are lost on restart; applications manage resumption.
- No automatic partition assignment/rebalancing, producer deduplication, or exactly-once delivery.
- Payload checksums and configurable acknowledgement modes are not implemented.
- The Java client serializes calls and has no automatic retry, reconnect, routing, or async API.
- ACL grants and quotas are process-local; persistent policy administration and automatic certificate rotation are not implemented.

## Performance

Reference targets are **500,000 records/s in memory** and **250,000 records/s durable**, with 1 KiB records. These are qualification goals. A completed run must also satisfy consumption, latency, CPU, heap, and error criteria. A smoke run or an unqualified result does not establish sustained capacity.

The [benchmark guide](setu-benchmark/README.md) documents reproducible profiles, strict/report-only policies, host budgets, and JFR interpretation. It also records a local Windows Chronicle diagnostic of approximately 55K durable records/s, which did not qualify. Results from the former custom storage engine do not qualify Chronicle Queue.

The generated blocks below retain their source revision, host information, and qualification status. Read them against that revision rather than as measurements of the current checkout; CI refreshes them after new runs.

<!-- benchmark-metrics-badges:start -->
[![in-memory: 500.00K/s target, 445.06K/s CI, 89% unqualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39440746529)
[![file: 250.00K/s target, 86.23K/s CI, 34% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39440746529)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest recorded target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 3848.27 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39440746529)
[![memory resources: 100% CPU, 256 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39440746529)
[![memory JFR: 1.47 GiB/s alloc, 42.6 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39440746529)
[![file p99: 19461.57 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39440746529)
[![file resources: 59% CPU, 128 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39440746529)
[![file JFR: 0.41 GiB/s alloc, 6.6 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39440746529)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `4d1825894ab59f27974ad37be83c4f2b9cddcb57`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39440746529).

Runner: AMD EPYC 9V45 96-Core Processor; 1.6 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

Runner: AMD EPYC 9V74 80-Core Processor; 1.6 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

| Mode | Target/s | Median measured/s | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 445,065 | 3848.274 | 256 | 99.5817 | False |
| FILE | 250,000 | 86,229 | 19461.571 | 128 | 58.9358 | False |

| Mode | Repeat | Load | Measured/s | Consumed/s | p99 ms | CPU % | Heap slope MiB/s | Healthy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 77,150 | 77,167 | 11383.341 | 54.6665 | 0.105 | False |
| FILE | 1 | 80% | 86,272 | 86,289 | 16894.657 | 55.7894 | 0.253 | False |
| FILE | 1 | 100% | 86,229 | 86,246 | 19461.571 | 52.8519 | 0.036 | False |
| FILE | 2 | 50% | 84,676 | 84,693 | 9588.179 | 55.1449 | 0.068 | False |
| FILE | 2 | 80% | 86,093 | 86,101 | 16928.211 | 56.3295 | 0.226 | False |
| FILE | 2 | 100% | 86,229 | 86,246 | 19461.571 | 55.9583 | 0.252 | False |
| FILE | 3 | 50% | 86,289 | 86,306 | 9202.303 | 58.9358 | 0.130 | False |
| FILE | 3 | 80% | 86,366 | 86,366 | 16886.268 | 55.5196 | 0.006 | False |
| FILE | 3 | 100% | 86,340 | 86,357 | 19461.571 | 57.8520 | 0.034 | False |
| IN_MEMORY | 1 | 50% | 249,993 | 250,027 | 1.518 | 67.0392 | -0.118 | True |
| IN_MEMORY | 1 | 80% | 365,184 | 365,201 | 2585.788 | 99.4997 | 0.040 | False |
| IN_MEMORY | 1 | 100% | 435,302 | 435,371 | 3848.274 | 99.1005 | 0.025 | False |
| IN_MEMORY | 2 | 50% | 249,993 | 250,103 | 1.692 | 64.7686 | -0.092 | True |
| IN_MEMORY | 2 | 80% | 400,000 | 400,009 | 1.471 | 77.6874 | 0.086 | True |
| IN_MEMORY | 2 | 100% | 499,977 | 499,968 | 1.982 | 93.2678 | 0.350 | False |
| IN_MEMORY | 3 | 50% | 249,993 | 250,044 | 1.132 | 65.6236 | 0.278 | True |
| IN_MEMORY | 3 | 80% | 369,152 | 369,161 | 2273.313 | 99.5361 | 0.132 | False |
| IN_MEMORY | 3 | 100% | 445,065 | 445,073 | 3261.071 | 99.5817 | 0.014 | False |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | throughput 77,150/s <95% of offered 125,000/s; p99 11383.341 ms outside [0,10) ms |
| FILE | 1 | 80% | throughput 86,272/s <95% of offered 200,000/s; p99 16894.657 ms outside [0,100) ms |
| FILE | 1 | 100% | throughput 86,229/s <95% of offered 250,000/s; p99 19461.571 ms outside [0,100) ms |
| FILE | 2 | 50% | throughput 84,676/s <95% of offered 125,000/s; p99 9588.179 ms outside [0,10) ms |
| FILE | 2 | 80% | throughput 86,093/s <95% of offered 200,000/s; p99 16928.211 ms outside [0,100) ms |
| FILE | 2 | 100% | throughput 86,229/s <95% of offered 250,000/s; p99 19461.571 ms outside [0,100) ms |
| FILE | 3 | 50% | throughput 86,289/s <95% of offered 125,000/s; p99 9202.303 ms outside [0,10) ms |
| FILE | 3 | 80% | throughput 86,366/s <95% of offered 200,000/s; p99 16886.268 ms outside [0,100) ms |
| FILE | 3 | 100% | throughput 86,340/s <95% of offered 250,000/s; p99 19461.571 ms outside [0,100) ms |
| IN_MEMORY | 1 | 80% | throughput 365,184/s <95% of offered 400,000/s; p99 2585.788 ms outside [0,50) ms; CPU 99.4997% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 100% | throughput 435,302/s <95% of offered 500,000/s; p99 3848.274 ms outside [0,50) ms; CPU 99.1005% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 100% | CPU 93.2678% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 80% | throughput 369,152/s <95% of offered 400,000/s; p99 2273.313 ms outside [0,50) ms; CPU 99.5361% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 100% | throughput 445,065/s <95% of offered 500,000/s; p99 3261.071 ms outside [0,50) ms; CPU 99.5817% outside 0–85% of allocated budget |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 10.723 | 0.839 | 1.284 | 11383.341 |
| FILE | 1 | 80% | 10.486 | 0.768 | 0.910 | 16894.657 |
| FILE | 1 | 100% | 10.453 | 0.793 | 1.055 | 19461.571 |
| FILE | 2 | 50% | 10.527 | 0.856 | 1.105 | 9588.179 |
| FILE | 2 | 80% | 10.437 | 0.835 | 1.133 | 16928.211 |
| FILE | 2 | 100% | 10.502 | 0.893 | 1.118 | 19461.571 |
| FILE | 3 | 50% | 10.641 | 0.950 | 1.325 | 9202.303 |
| FILE | 3 | 80% | 10.568 | 0.880 | 1.232 | 16886.268 |
| FILE | 3 | 100% | 10.559 | 0.901 | 1.154 | 19461.571 |
| IN_MEMORY | 1 | 50% | 0.643 | 0.339 | 0.263 | 1.518 |
| IN_MEMORY | 1 | 80% | 4.159 | 2.284 | 1.793 | 2585.788 |
| IN_MEMORY | 1 | 100% | 4.444 | 2.550 | 1.756 | 3848.274 |
| IN_MEMORY | 2 | 50% | 0.659 | 0.356 | 0.260 | 1.692 |
| IN_MEMORY | 2 | 80% | 0.548 | 0.353 | 0.223 | 1.471 |
| IN_MEMORY | 2 | 100% | 0.637 | 0.469 | 0.334 | 1.982 |
| IN_MEMORY | 3 | 50% | 0.585 | 0.322 | 0.236 | 1.132 |
| IN_MEMORY | 3 | 80% | 4.104 | 2.202 | 1.905 | 2273.313 |
| IN_MEMORY | 3 | 100% | 4.168 | 2.435 | 1.590 | 3261.071 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 9049 | 53050.960920 | 9.830399 | 238.968481 |
| FILE | 1 | 2 | 10114 | 52981.551231 | 9.666559 | 28.965680 |
| FILE | 1 | 3 | 10109 | 53107.923855 | 9.633791 | 30.912993 |
| FILE | 2 | 1 | 9933 | 53047.698460 | 9.658367 | 23.129962 |
| FILE | 2 | 2 | 10093 | 52762.800161 | 9.568255 | 19.117474 |
| FILE | 2 | 3 | 10109 | 52066.498954 | 9.519103 | 22.555130 |
| FILE | 3 | 1 | 10119 | 51665.649635 | 9.584639 | 29.346329 |
| FILE | 3 | 2 | 10125 | 52088.067133 | 9.625599 | 29.723100 |
| FILE | 3 | 3 | 10122 | 51936.793781 | 9.658367 | 22.495168 |
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
