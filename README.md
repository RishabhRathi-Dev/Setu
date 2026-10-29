# Setu

**A single-node Java message broker with partitioned logs and durable acknowledgements.**

[![CI](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml/badge.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![Java 26](https://img.shields.io/badge/Java-26-2563eb)](pom.xml)
[![Maven Wrapper](https://img.shields.io/badge/build-Maven_Wrapper-2563eb)](.mvn/wrapper/maven-wrapper.properties)

[Quick start](#quick-start) · [Documentation](docs/README.md) · [Examples](examples/README.md) · [Contributing](CONTRIBUTING.md) · [Get help](SUPPORT.md)

Setu stores ordered records in topic partitions and exposes explicit produce, fetch, and consumer-progress operations. The default `FILE` engine uses OpenHFT Chronicle Queue; `IN_MEMORY` provides bounded, volatile storage. The implementation includes blocking and selector TCP transports, synchronous and bounded async Java clients, managed groups, identified producer retries, single-partition transactions, and an embedded mTLS security pipeline.

Setu is under active development. It runs as one broker process, with no replication or failover. File brokers recover active consumer memberships and acknowledged progress; security policies remain process-local. See [implementation boundaries](#implementation-boundaries) before choosing a deployment model.

## At a glance

- **Partitioned messaging:** explicit partition selection, ordered offsets, batch produce, and long-poll fetch.
- **Two storage modes:** durable daily Chronicle logs with group commit and optional file retention, or bounded memory with consumer-driven reclamation.
- **Recovery and processing:** managed-group generation fencing, idempotent producers, atomic consume/produce, and checksummed metadata checkpoints.
- **Java integration:** synchronous and bounded async clients, a PostgreSQL outbox/inbox adapter, and an embeddable broker.
- **Operations:** [Docker and Compose](docs/containers.md), loopback Prometheus metrics/readiness, [LGTM collection and propagated request traces](docs/observability.md), automatic journal compaction, and packaged distributions.
- **Embedded security:** mTLS identities, default-deny exact ACLs, and per-principal request/byte quotas.
- **Measured behavior:** functional TCP/mTLS tests, recovery and chaos checks, and benchmark reports that retain qualification failures.

The [recovery demo](examples/recovery-demo/README.md) exercises transaction retries and process-crash recovery. Run `python examples/recovery-demo/run.py` with JDK 26 and Python 3.10+. It drops a committed transaction reply, retries, checkpoints, forcibly terminates the broker, and verifies one output and the recovered input offset. Logs, metrics and environment evidence are saved in a fresh local directory.

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

### Containers

Build and start the broker with JDK 26 included:

```text
docker compose up --build -d
```

For Grafana, Loki, Tempo, Mimir and Alloy, use `docker compose -f compose.yaml -f compose.lgtm.yaml up --build -d`. [Container setup](docs/containers.md) covers client examples inside the image, CI-aligned resource limits, persistent storage, health checks and the [published-image workflow](.github/workflows/container.yml).

The native setup follows for development with a host JDK.

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
    transport --> framing["Frame admission<br/>and protocol"]
    framing --> dispatch[Request dispatch]
    dispatch --> broker[DefaultBroker]
    broker --> topics["Topics and partitions<br/>Consumer progress"]
    topics --> logs[MessageLog]
    logs --> file["FILE<br/>Group commit + Chronicle Queue"]
    logs --> memory["IN_MEMORY<br/>Immutable chunks + budget"]
    security["Embedded security<br/>mTLS, exact ACLs, quotas"] -.-> dispatch
```

Transport manages sockets and buffer admission; core owns protocol and broker semantics; storage maps logical offsets to physical records. The embedded secure server authenticates connections and authorizes requests before dispatch. The standalone properties launcher serves plaintext.

The [architecture guide](docs/architecture.md) includes module dependencies, a durable-write sequence, consumer lifecycle, backpressure, and restart behavior. The [wire reference](setu-core/README.md#protocol) describes framing and command IDs.

## Messaging and storage

Records are opaque byte arrays. Ordering and dense, zero-based offsets apply **within each partition**. Applications select partitions explicitly. Batch produce returns the first assigned offset; fetch returns whole records within a byte budget. Consumer acknowledgements use the next offset after the last processed record.

| Behavior | `FILE` (default) | `IN_MEMORY` |
| --- | --- | --- |
| Engine | Chronicle Queue 2026.7 with bounded group commit | Immutable chunks published with CAS |
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
| [setu-client/](setu-client/README.md) | Synchronous and bounded async Java clients, plaintext and TLS |
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
- Metadata checkpoints compact historical transaction payloads while preserving fencing and progress. They pause broker requests; producer/group deletion and incremental snapshots remain unsupported. See [checkpoint recovery and compatibility](docs/journal-checkpoints.md).
- ACL grants and quotas are process-local; persistent policy administration and automatic certificate rotation are not implemented.

The [PostgreSQL outbox/inbox adapter](docs/postgres-outbox.md) supports transactional publication intent and deduplicated database effects. Outbox publication may repeat; its PostgreSQL transaction is separate from Setu's broker transaction boundary.

## Performance

Reference targets are **500,000 records/s in memory** and **250,000 records/s durable**, with 1 KiB records. These are qualification goals. A completed run must also satisfy consumption, latency, CPU, heap, and error criteria. A smoke run or an unqualified result does not establish sustained capacity.

The [benchmark guide](setu-benchmark/README.md) documents reproducible profiles, strict/report-only policies, host budgets, and JFR interpretation. Results from a different storage engine do not qualify Chronicle Queue.

The generated blocks below retain their source revision, host information, and qualification status. Read them against that revision rather than as measurements of the current checkout; CI refreshes them after new runs.

<!-- benchmark-metrics-badges:start -->
[![in-memory: 500.00K/s target, 499.99K/s CI, 100% qualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40886750864)
[![file: 250.00K/s target, 106.75K/s CI, 43% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40886750864)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest recorded target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 0.68 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40886750864)
[![memory resources: 73% CPU, 254 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40886750864)
[![memory JFR: 1.65 GiB/s alloc, 8.8 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40886750864)
[![file p99: 22800.24 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40886750864)
[![file resources: 32% CPU, 126 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40886750864)
[![file JFR: 0.46 GiB/s alloc, 6.4 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40886750864)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `3632046ab21395f29e4d443c10d98b1011622e6f`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40886750864).

Runner: AMD EPYC 9V74 80-Core Processor; 3.2 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

Runner: AMD EPYC 7763 64-Core Processor; 3.2 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

| Mode | Target/s | Median measured/s | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 499,994 | 0.685 | 254 | 72.9258 | True |
| FILE | 250,000 | 106,752 | 22800.237 | 126 | 32.2259 | False |

| Mode | Repeat | Load | Measured/s | Consumed/s | p99 ms | CPU % | Heap slope MiB/s | Healthy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 105,617 | 105,626 | 4529.848 | 32.2259 | 0.053 | False |
| FILE | 1 | 80% | 96,333 | 97,075 | 16013.853 | 24.6973 | 0.061 | False |
| FILE | 1 | 100% | 106,752 | 104,730 | 18001.953 | 23.9167 | 0.209 | False |
| FILE | 2 | 50% | 102,076 | 102,161 | 6161.433 | 23.1869 | 0.189 | False |
| FILE | 2 | 80% | 63,855 | 63,872 | 20367.540 | 22.2492 | -0.295 | False |
| FILE | 2 | 100% | 112,990 | 112,998 | 16374.563 | 29.0509 | 0.113 | False |
| FILE | 3 | 50% | 96,171 | 95,659 | 7839.154 | 28.4366 | -0.213 | False |
| FILE | 3 | 80% | 85,137 | 85,205 | 17280.532 | 27.2597 | -0.060 | False |
| FILE | 3 | 100% | 55,415 | 55,433 | 22800.237 | 23.3848 | 0.397 | False |
| IN_MEMORY | 1 | 50% | 249,993 | 250,027 | 0.536 | 52.7391 | 0.158 | True |
| IN_MEMORY | 1 | 80% | 400,000 | 400,009 | 0.575 | 64.4467 | 0.248 | True |
| IN_MEMORY | 1 | 100% | 499,994 | 500,002 | 0.658 | 71.3003 | 0.250 | True |
| IN_MEMORY | 2 | 50% | 249,993 | 250,027 | 0.591 | 53.5826 | 0.291 | True |
| IN_MEMORY | 2 | 80% | 400,000 | 400,009 | 0.616 | 65.8840 | 0.345 | True |
| IN_MEMORY | 2 | 100% | 499,994 | 500,002 | 0.685 | 72.9258 | 0.013 | True |
| IN_MEMORY | 3 | 50% | 249,993 | 250,061 | 0.604 | 53.4054 | 0.115 | True |
| IN_MEMORY | 3 | 80% | 400,000 | 400,009 | 0.589 | 65.1542 | 0.382 | True |
| IN_MEMORY | 3 | 100% | 499,994 | 500,002 | 0.670 | 72.1016 | 0.049 | True |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | throughput 105,617/s <95% of offered 125,000/s; p99 4529.848 ms outside [0,10) ms |
| FILE | 1 | 80% | throughput 96,333/s <95% of offered 200,000/s; p99 16013.853 ms outside [0,100) ms |
| FILE | 1 | 100% | throughput 106,752/s <95% of offered 250,000/s; p99 18001.953 ms outside [0,100) ms |
| FILE | 2 | 50% | throughput 102,076/s <95% of offered 125,000/s; p99 6161.433 ms outside [0,10) ms |
| FILE | 2 | 80% | throughput 63,855/s <95% of offered 200,000/s; p99 20367.540 ms outside [0,100) ms |
| FILE | 2 | 100% | throughput 112,990/s <95% of offered 250,000/s; p99 16374.563 ms outside [0,100) ms |
| FILE | 3 | 50% | throughput 96,171/s <95% of offered 125,000/s; p99 7839.154 ms outside [0,10) ms |
| FILE | 3 | 80% | throughput 85,137/s <95% of offered 200,000/s; p99 17280.532 ms outside [0,100) ms |
| FILE | 3 | 100% | throughput 55,415/s <95% of offered 250,000/s; p99 22800.237 ms outside [0,100) ms |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 69.140 | 0.615 | 28.557 | 4529.848 |
| FILE | 1 | 80% | 74.121 | 0.639 | 57.508 | 16013.853 |
| FILE | 1 | 100% | 64.913 | 0.734 | 54.723 | 18001.953 |
| FILE | 2 | 50% | 76.218 | 0.697 | 74.842 | 6161.433 |
| FILE | 2 | 80% | 119.210 | 0.541 | 98.763 | 20367.540 |
| FILE | 2 | 100% | 38.207 | 0.615 | 20.414 | 16374.563 |
| FILE | 3 | 50% | 67.305 | 0.577 | 38.142 | 7839.154 |
| FILE | 3 | 80% | 87.097 | 0.570 | 37.782 | 17280.532 |
| FILE | 3 | 100% | 203.817 | 0.504 | 102.171 | 22800.237 |
| IN_MEMORY | 1 | 50% | 0.409 | 0.302 | 0.175 | 0.536 |
| IN_MEMORY | 1 | 80% | 0.411 | 0.346 | 0.247 | 0.575 |
| IN_MEMORY | 1 | 100% | 0.445 | 0.385 | 0.270 | 0.658 |
| IN_MEMORY | 2 | 50% | 0.461 | 0.366 | 0.180 | 0.591 |
| IN_MEMORY | 2 | 80% | 0.449 | 0.404 | 0.258 | 0.616 |
| IN_MEMORY | 2 | 100% | 0.494 | 0.439 | 0.274 | 0.685 |
| IN_MEMORY | 3 | 50% | 0.438 | 0.314 | 0.187 | 0.604 |
| IN_MEMORY | 3 | 80% | 0.413 | 0.355 | 0.243 | 0.589 |
| IN_MEMORY | 3 | 100% | 0.456 | 0.412 | 0.273 | 0.670 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 21660 | 67469.342656 | 48.398335 | 222.903770 |
| FILE | 1 | 2 | 20282 | 71971.878130 | 51.937279 | 639.427718 |
| FILE | 1 | 3 | 22288 | 69766.017146 | 44.040191 | 426.043086 |
| FILE | 2 | 1 | 20302 | 71084.967503 | 64.782335 | 245.218058 |
| FILE | 2 | 2 | 13139 | 72957.301641 | 99.745791 | 382.440051 |
| FILE | 2 | 3 | 23255 | 70346.544821 | 24.952831 | 226.105757 |
| FILE | 3 | 1 | 20233 | 64499.652034 | 37.060607 | 643.179042 |
| FILE | 3 | 2 | 17834 | 71149.745538 | 55.640063 | 398.881246 |
| FILE | 3 | 3 | 11396 | 70817.592617 | 143.654911 | 801.040745 |
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
