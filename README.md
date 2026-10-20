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
[![in-memory: 500.00K/s target, 324.57K/s CI, 65% unqualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39438404315)
[![file: 250.00K/s target, 76.21K/s CI, 30% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39438404315)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest recorded target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 11861.49 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39438404315)
[![memory resources: 100% CPU, 256 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39438404315)
[![memory JFR: 1.07 GiB/s alloc, 29.9 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39438404315)
[![file p99: 21072.18 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39438404315)
[![file resources: 66% CPU, 128 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39438404315)
[![file JFR: 0.36 GiB/s alloc, 4.4 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39438404315)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `f60f10f0baac2bca0da92bf1b743c2d20fec8046`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39438404315).

Runner: AMD EPYC 9V74 80-Core Processor; 1.6 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

Runner: AMD EPYC 7763 64-Core Processor; 1.6 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

| Mode | Target/s | Median measured/s | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 324,565 | 11861.492 | 256 | 99.5416 | False |
| FILE | 250,000 | 76,211 | 21072.183 | 128 | 66.4155 | False |

| Mode | Repeat | Load | Measured/s | Consumed/s | p99 ms | CPU % | Heap slope MiB/s | Healthy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 67,772 | 67,789 | 13581.156 | 61.6614 | 0.108 | False |
| FILE | 1 | 80% | 72,747 | 72,764 | 18941.477 | 64.2277 | 0.229 | False |
| FILE | 1 | 100% | 79,881 | 79,898 | 20149.436 | 66.2274 | 0.184 | False |
| FILE | 2 | 50% | 76,851 | 76,843 | 11593.056 | 65.5808 | 0.217 | False |
| FILE | 2 | 80% | 76,971 | 76,988 | 18337.497 | 66.4155 | 0.287 | False |
| FILE | 2 | 100% | 76,211 | 76,228 | 20669.530 | 65.4138 | 0.094 | False |
| FILE | 3 | 50% | 62,199 | 62,217 | 14990.442 | 62.0808 | -0.036 | False |
| FILE | 3 | 80% | 66,953 | 66,970 | 19830.669 | 62.0393 | 0.175 | False |
| FILE | 3 | 100% | 70,775 | 70,801 | 21072.183 | 62.4773 | 0.134 | False |
| IN_MEMORY | 1 | 50% | 249,993 | 251,042 | 127.336 | 89.2047 | -0.696 | False |
| IN_MEMORY | 1 | 80% | 284,365 | 284,356 | 8556.380 | 99.4162 | 0.139 | False |
| IN_MEMORY | 1 | 100% | 324,565 | 324,676 | 10418.651 | 99.5416 | -0.203 | False |
| IN_MEMORY | 2 | 50% | 249,993 | 251,034 | 152.568 | 88.8108 | -0.362 | False |
| IN_MEMORY | 2 | 80% | 275,285 | 275,311 | 9227.469 | 99.4757 | 0.164 | False |
| IN_MEMORY | 2 | 100% | 324,983 | 324,796 | 10385.097 | 99.5394 | -0.458 | False |
| IN_MEMORY | 3 | 50% | 249,993 | 251,034 | 141.689 | 87.8715 | -0.174 | False |
| IN_MEMORY | 3 | 80% | 287,002 | 287,027 | 8388.608 | 99.3926 | -0.067 | False |
| IN_MEMORY | 3 | 100% | 300,484 | 300,484 | 11861.492 | 99.3286 | -0.138 | False |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | throughput 67,772/s <95% of offered 125,000/s; p99 13581.156 ms outside [0,10) ms |
| FILE | 1 | 80% | throughput 72,747/s <95% of offered 200,000/s; p99 18941.477 ms outside [0,100) ms |
| FILE | 1 | 100% | throughput 79,881/s <95% of offered 250,000/s; p99 20149.436 ms outside [0,100) ms |
| FILE | 2 | 50% | throughput 76,851/s <95% of offered 125,000/s; p99 11593.056 ms outside [0,10) ms |
| FILE | 2 | 80% | throughput 76,971/s <95% of offered 200,000/s; p99 18337.497 ms outside [0,100) ms |
| FILE | 2 | 100% | throughput 76,211/s <95% of offered 250,000/s; p99 20669.530 ms outside [0,100) ms |
| FILE | 3 | 50% | throughput 62,199/s <95% of offered 125,000/s; p99 14990.442 ms outside [0,10) ms |
| FILE | 3 | 80% | throughput 66,953/s <95% of offered 200,000/s; p99 19830.669 ms outside [0,100) ms |
| FILE | 3 | 100% | throughput 70,775/s <95% of offered 250,000/s; p99 21072.183 ms outside [0,100) ms |
| IN_MEMORY | 1 | 50% | p99 127.336 ms outside [0,5) ms; CPU 89.2047% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 80% | throughput 284,365/s <95% of offered 400,000/s; p99 8556.380 ms outside [0,50) ms; CPU 99.4162% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 100% | throughput 324,565/s <95% of offered 500,000/s; p99 10418.651 ms outside [0,50) ms; CPU 99.5416% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 50% | p99 152.568 ms outside [0,5) ms; CPU 88.8108% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 80% | throughput 275,285/s <95% of offered 400,000/s; p99 9227.469 ms outside [0,50) ms; CPU 99.4757% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 100% | throughput 324,983/s <95% of offered 500,000/s; p99 10385.097 ms outside [0,50) ms; CPU 99.5394% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 50% | p99 141.689 ms outside [0,5) ms; CPU 87.8715% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 80% | throughput 287,002/s <95% of offered 400,000/s; p99 8388.608 ms outside [0,50) ms; CPU 99.3926% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 100% | throughput 300,484/s <95% of offered 500,000/s; p99 11861.492 ms outside [0,50) ms; CPU 99.3286% outside 0–85% of allocated budget |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 46.367 | 0.878 | 1.530 | 13581.156 |
| FILE | 1 | 80% | 25.313 | 0.858 | 1.323 | 18941.477 |
| FILE | 1 | 100% | 14.574 | 0.887 | 1.326 | 20149.436 |
| FILE | 2 | 50% | 21.561 | 0.989 | 1.609 | 11593.056 |
| FILE | 2 | 80% | 35.783 | 0.897 | 1.023 | 18337.497 |
| FILE | 2 | 100% | 19.726 | 0.866 | 1.258 | 20669.530 |
| FILE | 3 | 50% | 89.719 | 0.914 | 1.642 | 14990.442 |
| FILE | 3 | 80% | 61.702 | 0.865 | 1.079 | 19830.669 |
| FILE | 3 | 100% | 70.779 | 0.885 | 1.187 | 21072.183 |
| IN_MEMORY | 1 | 50% | 2.032 | 0.821 | 0.693 | 127.336 |
| IN_MEMORY | 1 | 80% | 4.956 | 2.478 | 2.070 | 8556.380 |
| IN_MEMORY | 1 | 100% | 5.235 | 2.824 | 2.051 | 10418.651 |
| IN_MEMORY | 2 | 50% | 1.912 | 0.737 | 0.620 | 152.568 |
| IN_MEMORY | 2 | 80% | 5.595 | 2.695 | 2.185 | 9227.469 |
| IN_MEMORY | 2 | 100% | 5.362 | 3.162 | 1.997 | 10385.097 |
| IN_MEMORY | 3 | 50% | 1.672 | 0.700 | 0.557 | 141.689 |
| IN_MEMORY | 3 | 80% | 4.743 | 2.523 | 1.946 | 8388.608 |
| IN_MEMORY | 3 | 100% | 4.506 | 2.298 | 1.878 | 11861.492 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 7952 | 53687.346168 | 45.481983 | 168.882389 |
| FILE | 1 | 2 | 8529 | 53987.021802 | 24.788991 | 184.169911 |
| FILE | 1 | 3 | 9365 | 53429.899599 | 14.065663 | 117.836226 |
| FILE | 2 | 1 | 9010 | 52079.332286 | 19.447807 | 167.879010 |
| FILE | 2 | 2 | 9024 | 53204.880944 | 33.472511 | 177.689650 |
| FILE | 2 | 3 | 8935 | 53627.339877 | 19.202047 | 200.317479 |
| FILE | 3 | 1 | 7296 | 52161.971658 | 82.051071 | 368.826606 |
| FILE | 3 | 2 | 7849 | 52878.929044 | 50.954239 | 370.324980 |
| FILE | 3 | 3 | 8298 | 53125.302156 | 63.012863 | 207.259869 |
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
