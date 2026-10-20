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
[![in-memory: 500.00K/s target, 359.30K/s CI, 72% unqualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39429141720)
[![file: 250.00K/s target, 84.42K/s CI, 34% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39429141720)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest recorded target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 10116.66 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39429141720)
[![memory resources: 100% CPU, 256 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39429141720)
[![memory JFR: 1.18 GiB/s alloc, 34.3 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39429141720)
[![file p99: 19730.01 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39429141720)
[![file resources: 59% CPU, 126 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39429141720)
[![file JFR: 0.40 GiB/s alloc, 5.5 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39429141720)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `c4547727729df01854eff6dc07ae1aca5d3e4579`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39429141720).

Runner: AMD EPYC 9V45 96-Core Processor; 1.6 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

Runner: INTEL(R) XEON(R) PLATINUM 8573C; 1.6 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

| Mode | Target/s | Median measured/s | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 359,296 | 10116.661 | 256 | 99.6403 | False |
| FILE | 250,000 | 84,420 | 19730.006 | 126 | 59.1219 | False |

| Mode | Repeat | Load | Measured/s | Consumed/s | p99 ms | CPU % | Heap slope MiB/s | Healthy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 76,066 | 76,083 | 11626.611 | 52.8725 | 0.277 | False |
| FILE | 1 | 80% | 83,593 | 83,610 | 17297.310 | 55.3530 | 0.082 | False |
| FILE | 1 | 100% | 84,420 | 84,429 | 19679.674 | 55.9564 | 0.049 | False |
| FILE | 2 | 50% | 81,135 | 81,135 | 10745.807 | 56.2082 | 0.019 | False |
| FILE | 2 | 80% | 82,381 | 82,398 | 17465.082 | 55.8110 | 0.231 | False |
| FILE | 2 | 100% | 84,932 | 84,949 | 19629.343 | 56.5824 | 0.144 | False |
| FILE | 3 | 50% | 84,855 | 84,873 | 9546.236 | 59.1219 | -0.014 | False |
| FILE | 3 | 80% | 84,608 | 84,625 | 17137.926 | 55.7690 | 0.153 | False |
| FILE | 3 | 100% | 84,019 | 84,028 | 19730.006 | 56.7276 | 0.075 | False |
| IN_MEMORY | 1 | 50% | 249,993 | 250,061 | 1.704 | 58.8120 | -0.051 | True |
| IN_MEMORY | 1 | 80% | 322,825 | 322,825 | 5746.196 | 99.5412 | -0.169 | False |
| IN_MEMORY | 1 | 100% | 329,677 | 329,668 | 10116.661 | 99.4963 | -0.369 | False |
| IN_MEMORY | 2 | 50% | 249,993 | 251,025 | 33.161 | 66.5406 | -0.030 | False |
| IN_MEMORY | 2 | 80% | 364,723 | 364,843 | 2629.829 | 99.6403 | -0.140 | False |
| IN_MEMORY | 2 | 100% | 364,587 | 364,501 | 8065.647 | 99.4337 | -0.170 | False |
| IN_MEMORY | 3 | 50% | 249,993 | 250,010 | 1.708 | 63.0619 | 0.079 | True |
| IN_MEMORY | 3 | 80% | 392,883 | 392,926 | 707.789 | 99.6023 | -0.200 | False |
| IN_MEMORY | 3 | 100% | 359,296 | 359,287 | 8371.831 | 99.5207 | 0.011 | False |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | throughput 76,066/s <95% of offered 125,000/s; p99 11626.611 ms outside [0,10) ms |
| FILE | 1 | 80% | throughput 83,593/s <95% of offered 200,000/s; p99 17297.310 ms outside [0,100) ms |
| FILE | 1 | 100% | throughput 84,420/s <95% of offered 250,000/s; p99 19679.674 ms outside [0,100) ms |
| FILE | 2 | 50% | throughput 81,135/s <95% of offered 125,000/s; p99 10745.807 ms outside [0,10) ms |
| FILE | 2 | 80% | throughput 82,381/s <95% of offered 200,000/s; p99 17465.082 ms outside [0,100) ms |
| FILE | 2 | 100% | throughput 84,932/s <95% of offered 250,000/s; p99 19629.343 ms outside [0,100) ms |
| FILE | 3 | 50% | throughput 84,855/s <95% of offered 125,000/s; p99 9546.236 ms outside [0,10) ms |
| FILE | 3 | 80% | throughput 84,608/s <95% of offered 200,000/s; p99 17137.926 ms outside [0,100) ms |
| FILE | 3 | 100% | throughput 84,019/s <95% of offered 250,000/s; p99 19730.006 ms outside [0,100) ms |
| IN_MEMORY | 1 | 80% | throughput 322,825/s <95% of offered 400,000/s; p99 5746.196 ms outside [0,50) ms; CPU 99.5412% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 100% | throughput 329,677/s <95% of offered 500,000/s; p99 10116.661 ms outside [0,50) ms; CPU 99.4963% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 50% | p99 33.161 ms outside [0,5) ms |
| IN_MEMORY | 2 | 80% | throughput 364,723/s <95% of offered 400,000/s; p99 2629.829 ms outside [0,50) ms; CPU 99.6403% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 100% | throughput 364,587/s <95% of offered 500,000/s; p99 8065.647 ms outside [0,50) ms; CPU 99.4337% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 80% | p99 707.789 ms outside [0,50) ms; CPU 99.6023% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 100% | throughput 359,296/s <95% of offered 500,000/s; p99 8371.831 ms outside [0,50) ms; CPU 99.5207% outside 0–85% of allocated budget |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 10.863 | 0.773 | 1.174 | 11626.611 |
| FILE | 1 | 80% | 13.885 | 0.767 | 1.063 | 17297.310 |
| FILE | 1 | 100% | 10.559 | 0.740 | 0.854 | 19679.674 |
| FILE | 2 | 50% | 15.557 | 0.841 | 1.277 | 10745.807 |
| FILE | 2 | 80% | 15.229 | 0.794 | 0.930 | 17465.082 |
| FILE | 2 | 100% | 10.445 | 0.814 | 0.964 | 19629.343 |
| FILE | 3 | 50% | 10.568 | 0.879 | 1.207 | 9546.236 |
| FILE | 3 | 80% | 10.633 | 0.837 | 0.979 | 17137.926 |
| FILE | 3 | 100% | 10.764 | 0.817 | 1.009 | 19730.006 |
| IN_MEMORY | 1 | 50% | 0.644 | 0.379 | 0.265 | 1.704 |
| IN_MEMORY | 1 | 80% | 4.518 | 2.517 | 1.994 | 5746.196 |
| IN_MEMORY | 1 | 100% | 4.399 | 2.433 | 1.888 | 10116.661 |
| IN_MEMORY | 2 | 50% | 0.878 | 0.429 | 0.347 | 33.161 |
| IN_MEMORY | 2 | 80% | 4.866 | 2.920 | 1.858 | 2629.829 |
| IN_MEMORY | 2 | 100% | 4.739 | 2.908 | 1.805 | 8065.647 |
| IN_MEMORY | 3 | 50% | 0.622 | 0.388 | 0.257 | 1.708 |
| IN_MEMORY | 3 | 80% | 4.641 | 2.685 | 1.811 | 707.789 |
| IN_MEMORY | 3 | 100% | 4.039 | 2.355 | 1.751 | 8371.831 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 8923 | 53506.239175 | 10.035199 | 119.777617 |
| FILE | 1 | 2 | 9800 | 53287.212559 | 13.164543 | 51.989469 |
| FILE | 1 | 3 | 9897 | 53127.076446 | 9.732095 | 28.295706 |
| FILE | 2 | 1 | 9512 | 52254.660646 | 14.745599 | 68.548119 |
| FILE | 2 | 2 | 9658 | 53058.491861 | 14.426111 | 59.211490 |
| FILE | 2 | 3 | 9957 | 52809.571227 | 9.576447 | 29.426011 |
| FILE | 3 | 1 | 9951 | 51997.053184 | 9.576447 | 28.134157 |
| FILE | 3 | 2 | 9919 | 52489.150602 | 9.814015 | 19.000444 |
| FILE | 3 | 3 | 9850 | 52672.723318 | 9.928703 | 33.770034 |
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
