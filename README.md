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
- No automatic partition assignment/rebalancing, producer deduplication, or exactly-once delivery.
- New durable documents carry CRC32C checksums; older documents remain readable without checksum protection. Configurable acknowledgement modes are not implemented.
- `SetuClient` serializes calls. `AsyncSetuClient` adds bounded connection pooling, keyed partition routing, reconnects and optional read retries; writes are never automatically retried.
- ACL grants and quotas are process-local; persistent policy administration and automatic certificate rotation are not implemented.

The [PostgreSQL outbox/inbox adapter](docs/postgres-outbox.md) supports transactional publication intent and deduplicated database effects. Publication may repeat; broker-side transactions and producer deduplication remain unimplemented.

## Performance

Reference targets are **500,000 records/s in memory** and **250,000 records/s durable**, with 1 KiB records. These are qualification goals. A completed run must also satisfy consumption, latency, CPU, heap, and error criteria. A smoke run or an unqualified result does not establish sustained capacity.

The [benchmark guide](setu-benchmark/README.md) documents reproducible profiles, strict/report-only policies, host budgets, and JFR interpretation. It also records a local Windows Chronicle diagnostic of approximately 55K durable records/s, which did not qualify. Results from the former custom storage engine do not qualify Chronicle Queue.

The generated blocks below retain their source revision, host information, and qualification status. Read them against that revision rather than as measurements of the current checkout; CI refreshes them after new runs.

<!-- benchmark-metrics-badges:start -->
[![in-memory: 500.00K/s target, 289.47K/s CI, 58% unqualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39445874317)
[![file: 250.00K/s target, 126.48K/s CI, 51% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39445874317)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest recorded target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 12507.41 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39445874317)
[![memory resources: 100% CPU, 256 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39445874317)
[![memory JFR: 0.96 GiB/s alloc, 42.6 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39445874317)
[![file p99: 14864.61 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39445874317)
[![file resources: 98% CPU, 124 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39445874317)
[![file JFR: 0.55 GiB/s alloc, 14.8 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39445874317)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `b4122e1b1ba3c7697a904661a4fdd5b5326c3f37`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39445874317).

Runner: AMD EPYC 7763 64-Core Processor; 1.6 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

Runner: AMD EPYC 7763 64-Core Processor; 1.6 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

| Mode | Target/s | Median measured/s | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 289,468 | 12507.415 | 256 | 99.5773 | False |
| FILE | 250,000 | 126,481 | 14864.613 | 124 | 98.2291 | False |

| Mode | Repeat | Load | Measured/s | Consumed/s | p99 ms | CPU % | Heap slope MiB/s | Healthy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 124,996 | 125,022 | 124.649 | 92.5367 | 0.361 | False |
| FILE | 1 | 80% | 127,078 | 127,078 | 10829.693 | 90.4546 | 0.150 | False |
| FILE | 1 | 100% | 127,070 | 127,061 | 14621.344 | 89.2479 | 0.084 | False |
| FILE | 2 | 50% | 124,996 | 125,022 | 99.418 | 98.2291 | 0.206 | False |
| FILE | 2 | 80% | 126,993 | 127,002 | 10888.413 | 90.8748 | 0.124 | False |
| FILE | 2 | 100% | 126,481 | 126,498 | 14864.613 | 88.6845 | -0.021 | False |
| FILE | 3 | 50% | 124,834 | 124,834 | 110.756 | 98.2038 | 0.239 | False |
| FILE | 3 | 80% | 126,370 | 126,379 | 11039.408 | 89.4778 | 0.007 | False |
| FILE | 3 | 100% | 126,421 | 126,438 | 14696.841 | 88.7460 | 0.068 | False |
| IN_MEMORY | 1 | 50% | 249,993 | 251,042 | 165.151 | 88.7879 | 0.186 | False |
| IN_MEMORY | 1 | 80% | 271,795 | 271,804 | 9529.459 | 99.4154 | 0.246 | False |
| IN_MEMORY | 1 | 100% | 315,042 | 315,085 | 10989.076 | 99.5773 | 0.298 | False |
| IN_MEMORY | 2 | 50% | 249,993 | 251,051 | 492.044 | 90.8932 | -0.321 | False |
| IN_MEMORY | 2 | 80% | 282,274 | 282,266 | 8774.484 | 99.4363 | -0.329 | False |
| IN_MEMORY | 2 | 100% | 289,382 | 289,374 | 12507.415 | 99.3916 | -0.015 | False |
| IN_MEMORY | 3 | 50% | 249,993 | 251,025 | 141.165 | 89.0196 | 0.399 | False |
| IN_MEMORY | 3 | 80% | 307,925 | 307,959 | 6861.881 | 99.4984 | 0.664 | False |
| IN_MEMORY | 3 | 100% | 289,468 | 289,468 | 12499.026 | 99.4350 | -0.025 | False |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | p99 124.649 ms outside [0,10) ms; CPU 92.5367% outside 0–85% of allocated budget |
| FILE | 1 | 80% | throughput 127,078/s <95% of offered 200,000/s; p99 10829.693 ms outside [0,100) ms; CPU 90.4546% outside 0–85% of allocated budget |
| FILE | 1 | 100% | throughput 127,070/s <95% of offered 250,000/s; p99 14621.344 ms outside [0,100) ms; CPU 89.2479% outside 0–85% of allocated budget |
| FILE | 2 | 50% | p99 99.418 ms outside [0,10) ms; CPU 98.2291% outside 0–85% of allocated budget |
| FILE | 2 | 80% | throughput 126,993/s <95% of offered 200,000/s; p99 10888.413 ms outside [0,100) ms; CPU 90.8748% outside 0–85% of allocated budget |
| FILE | 2 | 100% | throughput 126,481/s <95% of offered 250,000/s; p99 14864.613 ms outside [0,100) ms; CPU 88.6845% outside 0–85% of allocated budget |
| FILE | 3 | 50% | p99 110.756 ms outside [0,10) ms; CPU 98.2038% outside 0–85% of allocated budget |
| FILE | 3 | 80% | throughput 126,370/s <95% of offered 200,000/s; p99 11039.408 ms outside [0,100) ms; CPU 89.4778% outside 0–85% of allocated budget |
| FILE | 3 | 100% | throughput 126,421/s <95% of offered 250,000/s; p99 14696.841 ms outside [0,100) ms; CPU 88.7460% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 50% | p99 165.151 ms outside [0,5) ms; CPU 88.7879% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 80% | throughput 271,795/s <95% of offered 400,000/s; p99 9529.459 ms outside [0,50) ms; CPU 99.4154% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 100% | throughput 315,042/s <95% of offered 500,000/s; p99 10989.076 ms outside [0,50) ms; CPU 99.5773% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 50% | p99 492.044 ms outside [0,5) ms; CPU 90.8932% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 80% | throughput 282,274/s <95% of offered 400,000/s; p99 8774.484 ms outside [0,50) ms; CPU 99.4363% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 100% | throughput 289,382/s <95% of offered 500,000/s; p99 12507.415 ms outside [0,50) ms; CPU 99.3916% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 50% | p99 141.165 ms outside [0,5) ms; CPU 89.0196% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 80% | throughput 307,925/s <95% of offered 400,000/s; p99 6861.881 ms outside [0,50) ms; CPU 99.4984% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 100% | throughput 289,468/s <95% of offered 500,000/s; p99 12499.026 ms outside [0,50) ms; CPU 99.4350% outside 0–85% of allocated budget |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 7.086 | 1.834 | 2.218 | 124.649 |
| FILE | 1 | 80% | 32.866 | 2.279 | 2.365 | 10829.693 |
| FILE | 1 | 100% | 34.341 | 2.085 | 2.014 | 14621.344 |
| FILE | 2 | 50% | 7.152 | 1.620 | 1.787 | 99.418 |
| FILE | 2 | 80% | 34.275 | 2.312 | 2.206 | 10888.413 |
| FILE | 2 | 100% | 34.177 | 2.054 | 1.895 | 14864.613 |
| FILE | 3 | 50% | 8.135 | 1.766 | 2.130 | 110.756 |
| FILE | 3 | 80% | 34.832 | 2.035 | 1.927 | 11039.408 |
| FILE | 3 | 100% | 35.684 | 1.868 | 1.857 | 14696.841 |
| IN_MEMORY | 1 | 50% | 1.703 | 0.702 | 0.655 | 165.151 |
| IN_MEMORY | 1 | 80% | 6.226 | 2.726 | 2.413 | 9529.459 |
| IN_MEMORY | 1 | 100% | 5.493 | 3.086 | 2.159 | 10989.076 |
| IN_MEMORY | 2 | 50% | 3.129 | 1.077 | 1.306 | 492.044 |
| IN_MEMORY | 2 | 80% | 5.644 | 2.679 | 2.306 | 8774.484 |
| IN_MEMORY | 2 | 100% | 5.206 | 2.540 | 2.070 | 12507.415 |
| IN_MEMORY | 3 | 50% | 1.843 | 0.759 | 0.740 | 141.165 |
| IN_MEMORY | 3 | 80% | 5.902 | 3.228 | 2.273 | 6861.881 |
| IN_MEMORY | 3 | 100% | 5.145 | 2.390 | 2.202 | 12499.026 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 14651 | 19624.751143 | 4.796415 | 22.550192 |
| FILE | 1 | 2 | 14896 | 40022.599159 | 30.949375 | 42.662700 |
| FILE | 1 | 3 | 14895 | 40575.544255 | 31.948799 | 42.580067 |
| FILE | 2 | 1 | 14660 | 17452.139846 | 4.497407 | 20.589359 |
| FILE | 2 | 2 | 14885 | 40990.739498 | 31.653887 | 42.349951 |
| FILE | 2 | 3 | 14826 | 41078.821951 | 32.473087 | 43.441056 |
| FILE | 3 | 1 | 14655 | 17905.460556 | 4.849663 | 39.972769 |
| FILE | 3 | 2 | 14813 | 42430.633089 | 33.505279 | 46.646279 |
| FILE | 3 | 3 | 14819 | 43340.891620 | 34.209791 | 49.341221 |
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
