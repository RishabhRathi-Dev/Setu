# Setu

**A single-node Java message broker with partitioned logs and durable acknowledgements.**

[![CI](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml/badge.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![Java 26](https://img.shields.io/badge/Java-26-2563eb)](pom.xml)
[![Maven Wrapper](https://img.shields.io/badge/build-Maven_Wrapper-2563eb)](.mvn/wrapper/maven-wrapper.properties)

[Quick start](#quick-start) · [Documentation](docs/README.md) · [Examples](examples/README.md) · [Contributing](CONTRIBUTING.md) · [Get help](SUPPORT.md)

Setu stores ordered records in topic partitions and exposes explicit produce, fetch, and consumer-progress operations. The default `FILE` engine uses OpenHFT Chronicle Queue; `IN_MEMORY` provides bounded, volatile storage. The implementation includes blocking and selector TCP transports, a synchronous Java client, and an embedded mTLS security pipeline.

Setu is under active development. It runs as one broker process, with no replication or failover. File brokers recover active consumer memberships and acknowledged progress; security policies remain process-local. See [implementation boundaries](#implementation-boundaries) before choosing a deployment model.

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
- File brokers recover memberships and acknowledged progress. Memory brokers lose them on restart. Leave and expiry remove progress in both modes.
- FILE brokers offer opt-in managed assignment/rebalancing, producer deduplication and single-input/single-output-partition transactions. Producer sequencing and external effects still require application coordination; see [transaction guarantees and limits](docs/transactions.md).
- New durable documents carry CRC32C checksums; older documents remain readable without checksum protection. Per-request `DURABLE` (default) and weaker `ACCEPTED` acknowledgement modes are available.
- `SetuClient` serializes calls. `AsyncSetuClient` adds bounded pooling, keyed routing, reconnects and retries for reads and identified transactional/idempotent writes. Ordinary writes retain uncertain retry outcomes.
- ACL grants and quotas are process-local; persistent policy administration and automatic certificate rotation are not implemented.

The [PostgreSQL outbox/inbox adapter](docs/postgres-outbox.md) supports transactional publication intent and deduplicated database effects. Outbox publication may repeat; its PostgreSQL transaction is separate from Setu's broker transaction boundary.

## Performance

Reference targets are **500,000 records/s in memory** and **250,000 records/s durable**, with 1 KiB records. These are qualification goals. A completed run must also satisfy consumption, latency, CPU, heap, and error criteria. A smoke run or an unqualified result does not establish sustained capacity.

The [benchmark guide](setu-benchmark/README.md) documents reproducible profiles, strict/report-only policies, host budgets, and JFR interpretation. It also records a local Windows Chronicle diagnostic of approximately 55K durable records/s, which did not qualify. Results from the former custom storage engine do not qualify Chronicle Queue.

The generated blocks below retain their source revision, host information, and qualification status. Read them against that revision rather than as measurements of the current checkout; CI refreshes them after new runs.

<!-- benchmark-metrics-badges:start -->
[![in-memory: 500.00K/s target, 439.34K/s CI, 88% unqualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39633040613)
[![file: 250.00K/s target, 108.04K/s CI, 43% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39633040613)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest recorded target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 3812.62 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39633040613)
[![memory resources: 100% CPU, 256 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39633040613)
[![memory JFR: 1.45 GiB/s alloc, 62.8 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39633040613)
[![file p99: 17582.52 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39633040613)
[![file resources: 65% CPU, 126 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39633040613)
[![file JFR: 0.44 GiB/s alloc, 13.6 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39633040613)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `64679fd02fa1e40b05494a6842d74f767b7c725f`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39633040613).

Runner: AMD EPYC 7763 64-Core Processor; 1.6 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

Runner: AMD EPYC 9V45 96-Core Processor; 1.6 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

| Mode | Target/s | Median measured/s | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 439,339 | 3812.622 | 256 | 99.5183 | False |
| FILE | 250,000 | 108,041 | 17582.522 | 126 | 64.6854 | False |

| Mode | Repeat | Load | Measured/s | Consumed/s | p99 ms | CPU % | Heap slope MiB/s | Healthy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 104,175 | 99,849 | 4966.056 | 63.4366 | -0.097 | False |
| FILE | 1 | 80% | 105,523 | 96,939 | 14034.141 | 60.6244 | -0.043 | False |
| FILE | 1 | 100% | 108,288 | 103,398 | 16869.491 | 60.8110 | 0.135 | False |
| FILE | 2 | 50% | 105,310 | 95,846 | 4894.753 | 64.6854 | 0.055 | False |
| FILE | 2 | 80% | 105,011 | 98,756 | 14118.027 | 60.3711 | 0.046 | False |
| FILE | 2 | 100% | 102,118 | 100,949 | 17582.522 | 61.0605 | 0.025 | False |
| FILE | 3 | 50% | 106,189 | 97,510 | 4559.208 | 63.2263 | -0.066 | False |
| FILE | 3 | 80% | 104,235 | 99,055 | 14227.079 | 59.7063 | 0.043 | False |
| FILE | 3 | 100% | 108,041 | 100,779 | 16894.657 | 59.7899 | -0.049 | False |
| IN_MEMORY | 1 | 50% | 249,993 | 249,993 | 1.570 | 53.5403 | 0.202 | True |
| IN_MEMORY | 1 | 80% | 400,000 | 400,009 | 1.504 | 70.1233 | 0.186 | True |
| IN_MEMORY | 1 | 100% | 499,994 | 500,002 | 1.821 | 79.0394 | 0.265 | True |
| IN_MEMORY | 2 | 50% | 249,993 | 250,035 | 1.144 | 55.4988 | 0.512 | True |
| IN_MEMORY | 2 | 80% | 400,000 | 400,009 | 1.415 | 69.4766 | 0.185 | True |
| IN_MEMORY | 2 | 100% | 439,339 | 438,366 | 3563.061 | 93.6441 | 1.724 | False |
| IN_MEMORY | 3 | 50% | 249,993 | 250,086 | 1.545 | 55.0195 | 0.160 | True |
| IN_MEMORY | 3 | 80% | 400,000 | 400,009 | 1.535 | 68.2059 | -0.063 | True |
| IN_MEMORY | 3 | 100% | 435,772 | 435,797 | 3812.622 | 99.5183 | -0.325 | False |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | throughput 104,175/s <95% of offered 125,000/s; p99 4966.056 ms outside [0,10) ms |
| FILE | 1 | 80% | consumption 91.9% <95%; throughput 105,523/s <95% of offered 200,000/s; p99 14034.141 ms outside [0,100) ms |
| FILE | 1 | 100% | throughput 108,288/s <95% of offered 250,000/s; p99 16869.491 ms outside [0,100) ms |
| FILE | 2 | 50% | consumption 91.0% <95%; throughput 105,310/s <95% of offered 125,000/s; p99 4894.753 ms outside [0,10) ms |
| FILE | 2 | 80% | consumption 94.0% <95%; throughput 105,011/s <95% of offered 200,000/s; p99 14118.027 ms outside [0,100) ms |
| FILE | 2 | 100% | throughput 102,118/s <95% of offered 250,000/s; p99 17582.522 ms outside [0,100) ms |
| FILE | 3 | 50% | consumption 91.8% <95%; throughput 106,189/s <95% of offered 125,000/s; p99 4559.208 ms outside [0,10) ms |
| FILE | 3 | 80% | throughput 104,235/s <95% of offered 200,000/s; p99 14227.079 ms outside [0,100) ms |
| FILE | 3 | 100% | consumption 93.3% <95%; throughput 108,041/s <95% of offered 250,000/s; p99 16894.657 ms outside [0,100) ms |
| IN_MEMORY | 2 | 100% | throughput 439,339/s <95% of offered 500,000/s; p99 3563.061 ms outside [0,50) ms; CPU 93.6441% outside 0–85% of allocated budget; heap slope 1.724 MiB/s >1 MiB/s |
| IN_MEMORY | 3 | 100% | throughput 435,772/s <95% of offered 500,000/s; p99 3812.622 ms outside [0,50) ms; CPU 99.5183% outside 0–85% of allocated budget |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 39.125 | 3.205 | 37.487 | 4966.056 |
| FILE | 1 | 80% | 40.206 | 3.086 | 39.158 | 14034.141 |
| FILE | 1 | 100% | 39.223 | 3.047 | 37.913 | 16869.491 |
| FILE | 2 | 50% | 39.649 | 3.430 | 38.896 | 4894.753 |
| FILE | 2 | 80% | 40.436 | 2.986 | 39.289 | 14118.027 |
| FILE | 2 | 100% | 41.943 | 3.232 | 39.911 | 17582.522 |
| FILE | 3 | 50% | 39.092 | 3.326 | 38.011 | 4559.208 |
| FILE | 3 | 80% | 40.796 | 2.988 | 39.748 | 14227.079 |
| FILE | 3 | 100% | 39.158 | 2.845 | 38.994 | 16894.657 |
| IN_MEMORY | 1 | 50% | 0.658 | 0.269 | 0.131 | 1.570 |
| IN_MEMORY | 1 | 80% | 0.482 | 0.319 | 0.252 | 1.504 |
| IN_MEMORY | 1 | 100% | 0.509 | 0.372 | 0.266 | 1.821 |
| IN_MEMORY | 2 | 50% | 0.491 | 0.268 | 0.175 | 1.144 |
| IN_MEMORY | 2 | 80% | 0.469 | 0.304 | 0.235 | 1.415 |
| IN_MEMORY | 2 | 100% | 3.099 | 1.406 | 1.266 | 3563.061 |
| IN_MEMORY | 3 | 50% | 0.616 | 0.322 | 0.246 | 1.545 |
| IN_MEMORY | 3 | 80% | 0.480 | 0.311 | 0.242 | 1.535 |
| IN_MEMORY | 3 | 100% | 3.762 | 2.171 | 1.649 | 3812.622 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 20124 | 57757.840186 | 36.306943 | 103.842854 |
| FILE | 1 | 2 | 20016 | 53500.625663 | 36.044799 | 46.477199 |
| FILE | 1 | 3 | 21043 | 53217.524776 | 32.489471 | 47.242899 |
| FILE | 2 | 1 | 19889 | 54732.946464 | 36.044799 | 44.129879 |
| FILE | 2 | 2 | 19975 | 55936.493437 | 37.224447 | 46.787426 |
| FILE | 2 | 3 | 20091 | 56195.954142 | 38.141951 | 72.770494 |
| FILE | 3 | 1 | 20180 | 55261.768692 | 34.013183 | 45.954067 |
| FILE | 3 | 2 | 20073 | 56665.410250 | 36.732927 | 47.027380 |
| FILE | 3 | 3 | 20591 | 56346.124196 | 35.782655 | 47.042818 |
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
