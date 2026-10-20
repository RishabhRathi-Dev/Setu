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
[![in-memory: 500.00K/s target, 302.15K/s CI, 60% unqualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39442280367)
[![file: 250.00K/s target, 126.06K/s CI, 50% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39442280367)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest recorded target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 12750.68 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39442280367)
[![memory resources: 100% CPU, 256 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39442280367)
[![memory JFR: 1.00 GiB/s alloc, 34.4 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39442280367)
[![file p99: 14948.50 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39442280367)
[![file resources: 92% CPU, 126 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39442280367)
[![file JFR: 0.56 GiB/s alloc, 16.4 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39442280367)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `d5da234f4c2d54f9fc925f79610fccc6c0cd3c07`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39442280367).

Runner: AMD EPYC 9V74 80-Core Processor; 1.6 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

Runner: AMD EPYC 7763 64-Core Processor; 1.6 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

| Mode | Target/s | Median measured/s | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 302,148 | 12750.684 | 256 | 99.5185 | False |
| FILE | 250,000 | 126,063 | 14948.499 | 126 | 91.8749 | False |

| Mode | Repeat | Load | Measured/s | Consumed/s | p99 ms | CPU % | Heap slope MiB/s | Healthy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 124,996 | 125,013 | 96.862 | 91.5620 | 0.088 | False |
| FILE | 1 | 80% | 127,036 | 127,053 | 10838.082 | 83.7487 | 0.014 | False |
| FILE | 1 | 100% | 125,705 | 125,722 | 14948.499 | 82.8934 | 0.106 | False |
| FILE | 2 | 50% | 124,996 | 125,013 | 189.268 | 91.8749 | 0.323 | False |
| FILE | 2 | 80% | 126,592 | 126,583 | 10905.190 | 86.5196 | 0.052 | False |
| FILE | 2 | 100% | 126,063 | 126,080 | 14738.784 | 85.2254 | 0.023 | False |
| FILE | 3 | 50% | 124,996 | 125,013 | 37.978 | 91.5397 | 0.108 | False |
| FILE | 3 | 80% | 127,258 | 127,283 | 11173.626 | 84.6832 | -0.006 | False |
| FILE | 3 | 100% | 127,266 | 127,283 | 14705.230 | 84.9356 | 0.038 | False |
| IN_MEMORY | 1 | 50% | 249,993 | 251,017 | 90.636 | 89.5194 | -0.383 | False |
| IN_MEMORY | 1 | 80% | 270,413 | 270,404 | 9613.345 | 99.3329 | -0.193 | False |
| IN_MEMORY | 1 | 100% | 313,335 | 313,395 | 11114.906 | 99.5185 | 0.347 | False |
| IN_MEMORY | 2 | 50% | 249,993 | 251,025 | 106.365 | 91.0189 | 0.084 | False |
| IN_MEMORY | 2 | 80% | 267,964 | 267,955 | 9814.671 | 99.2874 | -0.028 | False |
| IN_MEMORY | 2 | 100% | 285,082 | 285,090 | 12750.684 | 99.3107 | 0.023 | False |
| IN_MEMORY | 3 | 50% | 249,993 | 250,982 | 130.548 | 90.4568 | -0.324 | False |
| IN_MEMORY | 3 | 80% | 301,747 | 301,747 | 7243.563 | 98.7604 | 0.125 | False |
| IN_MEMORY | 3 | 100% | 302,148 | 302,071 | 11777.606 | 99.5162 | -0.073 | False |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | p99 96.862 ms outside [0,10) ms; CPU 91.5620% outside 0–85% of allocated budget |
| FILE | 1 | 80% | throughput 127,036/s <95% of offered 200,000/s; p99 10838.082 ms outside [0,100) ms |
| FILE | 1 | 100% | throughput 125,705/s <95% of offered 250,000/s; p99 14948.499 ms outside [0,100) ms |
| FILE | 2 | 50% | p99 189.268 ms outside [0,10) ms; CPU 91.8749% outside 0–85% of allocated budget |
| FILE | 2 | 80% | throughput 126,592/s <95% of offered 200,000/s; p99 10905.190 ms outside [0,100) ms; CPU 86.5196% outside 0–85% of allocated budget |
| FILE | 2 | 100% | throughput 126,063/s <95% of offered 250,000/s; p99 14738.784 ms outside [0,100) ms; CPU 85.2254% outside 0–85% of allocated budget |
| FILE | 3 | 50% | p99 37.978 ms outside [0,10) ms; CPU 91.5397% outside 0–85% of allocated budget |
| FILE | 3 | 80% | throughput 127,258/s <95% of offered 200,000/s; p99 11173.626 ms outside [0,100) ms |
| FILE | 3 | 100% | throughput 127,266/s <95% of offered 250,000/s; p99 14705.230 ms outside [0,100) ms |
| IN_MEMORY | 1 | 50% | p99 90.636 ms outside [0,5) ms; CPU 89.5194% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 80% | throughput 270,413/s <95% of offered 400,000/s; p99 9613.345 ms outside [0,50) ms; CPU 99.3329% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 100% | throughput 313,335/s <95% of offered 500,000/s; p99 11114.906 ms outside [0,50) ms; CPU 99.5185% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 50% | p99 106.365 ms outside [0,5) ms; CPU 91.0189% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 80% | throughput 267,964/s <95% of offered 400,000/s; p99 9814.671 ms outside [0,50) ms; CPU 99.2874% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 100% | throughput 285,082/s <95% of offered 500,000/s; p99 12750.684 ms outside [0,50) ms; CPU 99.3107% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 50% | p99 130.548 ms outside [0,5) ms; CPU 90.4568% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 80% | throughput 301,747/s <95% of offered 400,000/s; p99 7243.563 ms outside [0,50) ms; CPU 98.7604% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 100% | throughput 302,148/s <95% of offered 500,000/s; p99 11777.606 ms outside [0,50) ms; CPU 99.5162% outside 0–85% of allocated budget |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 6.267 | 1.481 | 2.028 | 96.862 |
| FILE | 1 | 80% | 38.011 | 1.870 | 1.903 | 10838.082 |
| FILE | 1 | 100% | 38.633 | 1.833 | 1.959 | 14948.499 |
| FILE | 2 | 50% | 9.085 | 1.677 | 2.189 | 189.268 |
| FILE | 2 | 80% | 34.472 | 2.226 | 2.419 | 10905.190 |
| FILE | 2 | 100% | 37.880 | 1.772 | 1.777 | 14738.784 |
| FILE | 3 | 50% | 6.001 | 1.416 | 1.962 | 37.978 |
| FILE | 3 | 80% | 37.913 | 2.016 | 2.185 | 11173.626 |
| FILE | 3 | 100% | 36.700 | 1.905 | 2.007 | 14705.230 |
| IN_MEMORY | 1 | 50% | 1.656 | 0.762 | 0.586 | 90.636 |
| IN_MEMORY | 1 | 80% | 5.853 | 2.664 | 2.269 | 9613.345 |
| IN_MEMORY | 1 | 100% | 5.198 | 2.949 | 2.026 | 11114.906 |
| IN_MEMORY | 2 | 50% | 1.456 | 0.728 | 0.554 | 106.365 |
| IN_MEMORY | 2 | 80% | 5.452 | 2.548 | 1.949 | 9814.671 |
| IN_MEMORY | 2 | 100% | 4.891 | 2.400 | 1.805 | 12750.684 |
| IN_MEMORY | 3 | 50% | 1.855 | 0.689 | 0.528 | 130.548 |
| IN_MEMORY | 3 | 80% | 4.690 | 2.331 | 2.009 | 7243.563 |
| IN_MEMORY | 3 | 100% | 4.706 | 2.859 | 2.034 | 11777.606 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 14656 | 15469.124975 | 3.717119 | 24.699023 |
| FILE | 1 | 2 | 14891 | 42333.058305 | 36.667391 | 44.297506 |
| FILE | 1 | 3 | 14735 | 43206.670140 | 36.798463 | 43.256798 |
| FILE | 2 | 1 | 14668 | 15863.493831 | 5.021695 | 27.655411 |
| FILE | 2 | 2 | 14839 | 40875.124029 | 29.523967 | 114.527733 |
| FILE | 2 | 3 | 14777 | 43700.579725 | 36.470783 | 44.527873 |
| FILE | 3 | 1 | 14656 | 13923.259017 | 3.764223 | 21.569517 |
| FILE | 3 | 2 | 14917 | 41844.588898 | 35.848191 | 43.883143 |
| FILE | 3 | 3 | 14918 | 41568.457376 | 34.832383 | 42.658702 |
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
