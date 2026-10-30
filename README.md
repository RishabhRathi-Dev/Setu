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

### CI benchmarks

The badges and expandable report below come from resource-enforced CI runs. Contributor PC diagnostics follow in their own section. CI publication continues when the contributor attachment check fails; that check still reports its failure separately.

<!-- benchmark-metrics-badges:start -->
[![in-memory: 500.00K/s target, 499.99K/s CI, 100% qualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41123298137)
[![file: 250.00K/s target, 165.98K/s CI, 66% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41123298137)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest CI target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 0.68 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41123298137)
[![memory resources: 57% CPU, 254 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41123298137)
[![memory JFR: 1.65 GiB/s alloc, 9.0 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41123298137)
[![file p99: 11442.06 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41123298137)
[![file resources: 36% CPU, 126 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41123298137)
[![file JFR: 0.71 GiB/s alloc, 12.7 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41123298137)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `0d10072bc154341c0edb22afbd673fc6bd70bd51`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41123298137).

Runner: AMD EPYC 9V45 96-Core Processor; 4.0 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

Runner: AMD EPYC 7763 64-Core Processor; 4.0 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

Writes count unique records; each record needs one delivery in this workload. Write target achieved compares actual writes with requested writes. Delivery coverage compares unique deliveries with completed writes in the same window. Similar write and delivery rates do not guarantee the requested load or other limits were met.

| Mode | Reference writes/s | Median actual writes/s | Write target achieved | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 499,994 | 100.0% | 0.684 | 254 | 57.3582 | True |
| FILE | 250,000 | 165,982 | 66.4% | 11442.061 | 126 | 35.9302 | False |

**Fixed-target traffic**

| Mode | Repeat | Requested writes/s | Actual writes/s | Unique deliveries/s | Write target achieved | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 125,000 | 124,996 | 125,030 | 100.0% | 100.0% |
| FILE | 1 | 200,000 | 151,458 | 151,475 | 75.7% | 100.0% |
| FILE | 1 | 250,000 | 164,881 | 156,160 | 66.0% | 94.7% |
| FILE | 2 | 125,000 | 124,996 | 125,030 | 100.0% | 100.0% |
| FILE | 2 | 200,000 | 182,349 | 182,767 | 91.2% | 100.2% |
| FILE | 2 | 250,000 | 174,677 | 165,103 | 69.9% | 94.5% |
| FILE | 3 | 125,000 | 124,996 | 125,013 | 100.0% | 100.0% |
| FILE | 3 | 200,000 | 163,994 | 165,905 | 82.0% | 101.2% |
| FILE | 3 | 250,000 | 165,982 | 164,582 | 66.4% | 99.2% |
| IN_MEMORY | 1 | 250,000 | 249,993 | 250,044 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 400,000 | 400,000 | 400,009 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 500,000 | 499,994 | 500,002 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 250,000 | 249,993 | 250,027 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 400,000 | 400,000 | 400,000 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 500,000 | 499,994 | 500,002 | 100.0% | 100.0% |
| IN_MEMORY | 3 | 250,000 | 249,993 | 250,044 | 100.0% | 100.0% |
| IN_MEMORY | 3 | 400,000 | 400,000 | 400,000 | 100.0% | 100.0% |
| IN_MEMORY | 3 | 500,000 | 499,994 | 500,002 | 100.0% | 100.0% |

**Fixed-target latency and resource checks**

| Mode | Repeat | Load | p99 ms | CPU % | Heap slope MiB/s | Qualified window |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 571.474 | 29.4152 | 0.100 | False |
| FILE | 1 | 80% | 7201.620 | 20.7993 | 0.086 | False |
| FILE | 1 | 100% | 11442.061 | 20.3242 | 0.159 | False |
| FILE | 2 | 50% | 74.973 | 34.4902 | 0.264 | False |
| FILE | 2 | 80% | 2577.400 | 24.6405 | 0.049 | False |
| FILE | 2 | 100% | 10192.159 | 23.3913 | 0.019 | False |
| FILE | 3 | 50% | 3.211 | 35.9302 | -0.026 | True |
| FILE | 3 | 80% | 5360.321 | 24.1660 | 0.070 | False |
| FILE | 3 | 100% | 10804.527 | 22.4080 | 0.011 | False |
| IN_MEMORY | 1 | 50% | 0.571 | 42.2247 | 0.364 | True |
| IN_MEMORY | 1 | 80% | 0.606 | 52.0238 | 0.421 | True |
| IN_MEMORY | 1 | 100% | 0.677 | 57.3582 | 0.612 | True |
| IN_MEMORY | 2 | 50% | 0.549 | 42.8158 | 0.312 | True |
| IN_MEMORY | 2 | 80% | 0.588 | 51.6982 | 0.612 | True |
| IN_MEMORY | 2 | 100% | 0.666 | 57.2655 | 0.159 | True |
| IN_MEMORY | 3 | 50% | 0.585 | 42.2323 | 0.260 | True |
| IN_MEMORY | 3 | 80% | 0.611 | 51.7911 | 0.330 | True |
| IN_MEMORY | 3 | 100% | 0.684 | 57.1405 | 0.254 | True |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | p99 571.474 ms outside [0,10) ms |
| FILE | 1 | 80% | throughput 151,458/s <95% of offered 200,000/s; p99 7201.620 ms outside [0,100) ms |
| FILE | 1 | 100% | consumption 94.7% <95%; throughput 164,881/s <95% of offered 250,000/s; p99 11442.061 ms outside [0,100) ms |
| FILE | 2 | 50% | p99 74.973 ms outside [0,10) ms |
| FILE | 2 | 80% | throughput 182,349/s <95% of offered 200,000/s; p99 2577.400 ms outside [0,100) ms |
| FILE | 2 | 100% | consumption 94.5% <95%; throughput 174,677/s <95% of offered 250,000/s; p99 10192.159 ms outside [0,100) ms |
| FILE | 3 | 80% | throughput 163,994/s <95% of offered 200,000/s; p99 5360.321 ms outside [0,100) ms |
| FILE | 3 | 100% | throughput 165,982/s <95% of offered 250,000/s; p99 10804.527 ms outside [0,100) ms |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 15.606 | 0.385 | 7.856 | 571.474 |
| FILE | 1 | 80% | 9.216 | 0.636 | 8.163 | 7201.620 |
| FILE | 1 | 100% | 9.486 | 0.711 | 9.183 | 11442.061 |
| FILE | 2 | 50% | 4.014 | 0.349 | 3.123 | 74.973 |
| FILE | 2 | 80% | 8.229 | 1.068 | 8.032 | 2577.400 |
| FILE | 2 | 100% | 8.155 | 0.923 | 8.036 | 10192.159 |
| FILE | 3 | 50% | 2.759 | 0.367 | 2.041 | 3.211 |
| FILE | 3 | 80% | 9.380 | 1.133 | 8.471 | 5360.321 |
| FILE | 3 | 100% | 8.225 | 0.831 | 7.881 | 10804.527 |
| IN_MEMORY | 1 | 50% | 0.434 | 0.312 | 0.183 | 0.571 |
| IN_MEMORY | 1 | 80% | 0.435 | 0.361 | 0.253 | 0.606 |
| IN_MEMORY | 1 | 100% | 0.468 | 0.407 | 0.275 | 0.677 |
| IN_MEMORY | 2 | 50% | 0.418 | 0.312 | 0.182 | 0.549 |
| IN_MEMORY | 2 | 80% | 0.408 | 0.349 | 0.243 | 0.588 |
| IN_MEMORY | 2 | 100% | 0.459 | 0.399 | 0.270 | 0.666 |
| IN_MEMORY | 3 | 50% | 0.441 | 0.320 | 0.190 | 0.585 |
| IN_MEMORY | 3 | 80% | 0.434 | 0.363 | 0.255 | 0.611 |
| IN_MEMORY | 3 | 100% | 0.465 | 0.405 | 0.269 | 0.684 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 23122 | 30010.813153 | 11.632639 | 193.680019 |
| FILE | 1 | 2 | 29189 | 71323.220241 | 8.118271 | 42.734537 |
| FILE | 1 | 3 | 30464 | 69461.074714 | 8.609791 | 30.008151 |
| FILE | 2 | 1 | 22609 | 10210.284596 | 2.016255 | 15.272372 |
| FILE | 2 | 2 | 33708 | 64713.511734 | 7.589887 | 19.909918 |
| FILE | 2 | 3 | 32752 | 67028.678120 | 7.598079 | 13.373075 |
| FILE | 3 | 1 | 22672 | 9194.240783 | 1.198079 | 18.424440 |
| FILE | 3 | 2 | 31220 | 68909.670983 | 8.298495 | 36.544571 |
| FILE | 3 | 3 | 32279 | 69763.391474 | 7.671807 | 18.396600 |
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

### CI redline limits

Each CI run also doubles producer/consumer concurrency until a runtime limit under an enforced CPU/memory budget. These short exhaustion diagnostics show peak rates, sampled CPU/heap, latency and the terminal limit; consumption includes broadcast fanout. They do not qualify sustained capacity. The pre-push check runs the same ramp locally and includes its results in the contributor report below.

Compare completed deliveries with the full delivery demand (unique writes × consumers). The report shows this as delivery coverage; a high fanout rate alone does not show that consumers kept up. Fixed-target reports count unique deliveries and show write-target attainment separately. See [how to read the reports](setu-benchmark/README.md#reading-the-reports).

<!-- benchmark-redline:start -->
Source revision `0d10072bc154341c0edb22afbd673fc6bd70bd51`. [CI redline report and captures](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41123298137)

Resource-enforced redline diagnostics (unqualified)

Source revision `0d10072bc154341c0edb22afbd673fc6bd70bd51`; enforcement: systemd-cgroup-v2.

Host: Intel(R) Xeon(R) 6973P-C; filesystem/media: /dev/nvme1n1p1 ext4 (non-rotational). Allocation: 4 CPU cores, 14703 MiB process-tree memory; 8360 MiB worker heap, 1393 MiB direct memory; free-disk reserve 17.03 GiB.

Execution exit code: 0.

**Redline diagnostics: find a runtime limit (unqualified)**

**Unique writes/s** counts records appended once. This is a fanout workload: every consumer reads the same partition, so one write creates one delivery per consumer. **Deliveries needed/s = unique writes/s × consumers.** **Delivery coverage** is completed deliveries divided by (written records × consumers) within the same window.

For example, 100,000 writes/s with 8 consumers needs 800,000 deliveries/s. 400,000 completed deliveries/s covers 50% of that demand.

Coverage describes aggregate progress during the measurement window, not a record-loss rate or proof that every consumer caught up. It can slightly exceed 100% when operations cross a window boundary; n/a means no writes completed. A completed load step is not a health or capacity pass. Peak production is a short-window observation, not sustained capacity.

One fresh JVM per mode; record bytes 1024, 4096-record batches, 2s warmup and 10s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

| Mode | Highest unique writes/s | Consumers at that rate | Delivery coverage at that rate | Why the test stopped |
| --- | ---: | ---: | ---: | --- |
| FILE | 142,895 | 8 | 43.9% | Broker/client limit reached (BROKER_OR_CLIENT_LIMIT) |

**FILE: delivery progress**

The last measured stage (6, 32 consumers) covered **14.9%** of its delivery demand. The test stopped at stage 6 with 32 producers and 32 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 120,626 | 120,626 | 120,626 | 100.0% |
| 2 | 2 | 136,949 | 273,898 | 273,488 | 99.9% |
| 3 | 4 | 139,746 | 558,984 | 491,972 | 88.0% |
| 4 | 8 | 142,895 | 1,143,160 | 502,175 | 43.9% |
| 5 | 16 | 105,625 | 1,690,000 | 443,299 | 26.2% |
| 6 | 32 | 68,998 | 2,207,936 | 329,140 | 14.9% |

**FILE: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 10.02 | 29.3% | 1948.0 | 67.109 | Measured |
| 2 | 10.02 | 31.9% | 2878.0 | 134.218 | Measured |
| 3 | 10.02 | 33.9% | 5858.0 | 268.435 | Measured |
| 4 | 10.03 | 56.7% | 7260.0 | 536.871 | Measured |
| 5 | 10.04 | 94.7% | 3632.0 | 1073.742 | Measured |
| 6 | 8.79 | 95.2% | 1364.0 | 4294.967 | Broker/client limit reached |

FILE: JDK 26.0.2.1; Linux; filesystem ext4; CPU percentages use 4 cores as 100%; 8360 MiB maximum heap.
Admission budgets: requests 1045 MiB, responses 1045 MiB; fetch payload 4 MiB; retained log 2047 MiB.


Resource-enforced redline diagnostics (unqualified)

Source revision `0d10072bc154341c0edb22afbd673fc6bd70bd51`; enforcement: systemd-cgroup-v2.

Host: AMD EPYC 9V45 96-Core Processor; filesystem/media: /dev/nvme0n1p1 ext4 (non-rotational). Allocation: 4 CPU cores, 14636 MiB process-tree memory; 8320 MiB worker heap, 1386 MiB direct memory; free-disk reserve 17.03 GiB.

Execution exit code: 0.

**Redline diagnostics: find a runtime limit (unqualified)**

**Unique writes/s** counts records appended once. This is a fanout workload: every consumer reads the same partition, so one write creates one delivery per consumer. **Deliveries needed/s = unique writes/s × consumers.** **Delivery coverage** is completed deliveries divided by (written records × consumers) within the same window.

For example, 100,000 writes/s with 8 consumers needs 800,000 deliveries/s. 400,000 completed deliveries/s covers 50% of that demand.

Coverage describes aggregate progress during the measurement window, not a record-loss rate or proof that every consumer caught up. It can slightly exceed 100% when operations cross a window boundary; n/a means no writes completed. A completed load step is not a health or capacity pass. Peak production is a short-window observation, not sustained capacity.

One fresh JVM per mode; record bytes 1024, 4096-record batches, 2s warmup and 10s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

| Mode | Highest unique writes/s | Consumers at that rate | Delivery coverage at that rate | Why the test stopped |
| --- | ---: | ---: | ---: | --- |
| IN_MEMORY | 1,867,930 | 1 | 98.6% | Broker/client limit reached (BROKER_OR_CLIENT_LIMIT): QUOTA_EXCEEDED |

**IN_MEMORY: delivery progress**

The last measured stage (9, 256 consumers) covered **0.1%** of its delivery demand. The test stopped at stage 9 with 256 producers and 256 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 1,867,930 | 1,867,930 | 1,841,783 | 98.6% |
| 2 | 2 | 1,507,531 | 3,015,062 | 2,963,642 | 98.3% |
| 3 | 4 | 1,002,232 | 4,008,928 | 3,907,443 | 97.5% |
| 4 | 8 | 585,907 | 4,687,256 | 4,534,360 | 96.7% |
| 5 | 16 | 307,471 | 4,919,536 | 4,659,370 | 94.7% |
| 6 | 32 | 177,578 | 5,682,496 | 4,748,165 | 83.6% |
| 7 | 64 | 98,675 | 6,315,200 | 4,808,970 | 76.1% |
| 8 | 128 | 46,333 | 5,930,624 | 4,664,090 | 78.6% |
| 9 | 256 | 1,697,040 | 434,442,240 | 571,119 | 0.1% |

**IN_MEMORY: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 10.03 | 67.7% | 6248.0 | 8.389 | Measured |
| 2 | 10.04 | 82.9% | 2198.0 | 8.389 | Measured |
| 3 | 10.05 | 95.6% | 1582.0 | 8.389 | Measured |
| 4 | 10.05 | 98.7% | 4498.0 | 16.777 | Measured |
| 5 | 10.04 | 99.0% | 2244.0 | 33.554 | Measured |
| 6 | 10.06 | 98.9% | 2448.0 | 67.109 | Measured |
| 7 | 10.05 | 99.1% | 4662.0 | 268.435 | Measured |
| 8 | 11.05 | 99.0% | 2446.0 | 2147.484 | Measured |
| 9 | 0.25 | 0.0% | 0.0 | 268.435 | Broker/client limit reached |

IN_MEMORY: JDK 26.0.2.1; Linux; filesystem ext4; CPU percentages use 4 cores as 100%; 8320 MiB maximum heap.
Admission budgets: requests 1040 MiB, responses 1040 MiB; fetch payload 4 MiB; retained log 2047 MiB.

<!-- benchmark-redline:end -->

### Contributor PC benchmarks

<!-- benchmark-contributor-pc:start -->
Latest contributor PC run (diagnostic, unqualified)

[![memory writes (contributor PC): 500.00K/s target, 499.64K/s actual, 100%, unqualified](benchmark-badges/contributor-memory-throughput.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41123298137)
[![memory p99 (contributor PC): 10.69 ms at target](benchmark-badges/contributor-memory-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41123298137)
[![memory resources (contributor PC): 18.2% CPU, 496 MiB heap](benchmark-badges/contributor-memory-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41123298137)
[![memory JFR (contributor PC): 1.69 GiB/s alloc, 1.3 ms GC](benchmark-badges/contributor-memory-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41123298137)
[![file writes (contributor PC): 250.00K/s target, 54.99K/s actual, 22%, unqualified](benchmark-badges/contributor-file-throughput.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41123298137)
[![file p99 (contributor PC): 7717.52 ms at target](benchmark-badges/contributor-file-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41123298137)
[![file resources (contributor PC): 31.5% CPU, 506 MiB heap](benchmark-badges/contributor-file-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41123298137)
[![file JFR (contributor PC): 0.24 GiB/s alloc, 0.4 ms GC](benchmark-badges/contributor-file-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41123298137)

[Report, logs and downloadable results](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41123298137)

Contributor: Rishabh Rathi; finished 2026-10-30T20:26:14.461342+00:00; revision `5edc68d9a41d8adb1da20a276ea1882e85300068`.

Windows-11-10.0.26100-SP0; AMD Ryzen 5 7600X 6-Core Processor; 8 JVM active processors; 512 MiB heap; 128 MiB direct-memory limit; no OS resource quota.

JDK: openjdk version "26.0.2.1" 2026-08-18 OpenJDK Runtime Environment Temurin-26.0.2.1+1 (build 26.0.2.1+1) OpenJDK 64-Bit Server VM Temurin-26.0.2.1+1 (build 26.0.2.1+1, mixed mode, sharing). Media: Filesystem per worker; physical media not detected.

Two fresh JVMs per mode; 1 KiB records; 256-record batches; 5s minimum adaptive warmup and 10s measurement at each load. Broker and clients share the PC. These results are contributor supplied and do not qualify sustained capacity.

**Contributor PC summary (diagnostic, unqualified)**

CI-style summary: median actual writes/s across the two full-target windows; worst p99 at that target. Heap, average CPU, allocation rate and GC pause values are maxima across all fixed-target windows. Window checks may pass while these contributor measurements remain unqualified.

| Mode | Reference writes/s | Median actual writes/s | Write target achieved | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Window checks | Status |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| IN_MEMORY | 500,000 | 499,635 | 99.9% | 10.691 | 496.0 | 18.2205 | Missed | Unqualified |
| FILE | 250,000 | 54,989 | 22.0% | 7717.519 | 506.0 | 31.5224 | Missed | Unqualified |

**Fixed target: requested writes versus completed work**

These windows count unique records, with one delivery per record. Requested writes/s is the offered load; actual writes/s is the completed rate. Write target achieved compares actual with requested writes. Delivery coverage compares unique deliveries with completed writes. Consumers keeping up does not mean the write target was met.

| Mode | Repeat | Requested writes/s | Actual writes/s | Unique deliveries/s | Write target achieved | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| IN_MEMORY | 1 | 250,000 | 249,984 | 250,010 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 400,000 | 399,718 | 399,693 | 99.9% | 100.0% |
| IN_MEMORY | 1 | 500,000 | 499,661 | 499,738 | 99.9% | 100.0% |
| FILE | 1 | 125,000 | 50,176 | 50,227 | 40.1% | 100.1% |
| FILE | 1 | 200,000 | 55,910 | 55,910 | 28.0% | 100.0% |
| FILE | 1 | 250,000 | 55,475 | 55,475 | 22.2% | 100.0% |
| IN_MEMORY | 2 | 250,000 | 249,882 | 249,907 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 400,000 | 399,821 | 399,898 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 500,000 | 499,610 | 499,661 | 99.9% | 100.0% |
| FILE | 2 | 125,000 | 54,758 | 54,810 | 43.8% | 100.1% |
| FILE | 2 | 200,000 | 53,274 | 53,325 | 26.6% | 100.1% |
| FILE | 2 | 250,000 | 54,502 | 54,528 | 21.8% | 100.0% |

**Fixed target: latency and resource checks**

Window checks include throughput, consumption, latency, CPU and heap limits. A passed window remains a local diagnostic, not qualified sustained capacity.

| Mode | Repeat | Load | p99 ms | CPU budget used | Force p99 ms | Window checks |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 1 | 50% | 11.813 | 7.9% | 0.000 | Missed |
| IN_MEMORY | 1 | 80% | 10.011 | 12.1% | 0.000 | Passed |
| IN_MEMORY | 1 | 100% | 10.691 | 17.3% | 0.000 | Missed |
| FILE | 1 | 50% | 5926.552 | 31.5% | 7.148 | Missed |
| FILE | 1 | 80% | 7113.540 | 29.4% | 5.915 | Missed |
| FILE | 1 | 100% | 7692.354 | 28.6% | 5.804 | Missed |
| IN_MEMORY | 2 | 50% | 11.952 | 15.5% | 0.000 | Missed |
| IN_MEMORY | 2 | 80% | 10.060 | 8.6% | 0.000 | Passed |
| IN_MEMORY | 2 | 100% | 9.552 | 18.2% | 0.000 | Passed |
| FILE | 2 | 50% | 5540.676 | 28.6% | 6.152 | Missed |
| FILE | 2 | 80% | 7218.397 | 27.3% | 6.484 | Missed |
| FILE | 2 | 100% | 7717.519 | 28.3% | 6.255 | Missed |

**Redline diagnostics: find a runtime limit (unqualified)**

**Unique writes/s** counts records appended once. This is a fanout workload: every consumer reads the same partition, so one write creates one delivery per consumer. **Deliveries needed/s = unique writes/s × consumers.** **Delivery coverage** is completed deliveries divided by (written records × consumers) within the same window.

For example, 100,000 writes/s with 8 consumers needs 800,000 deliveries/s. 400,000 completed deliveries/s covers 50% of that demand.

Coverage describes aggregate progress during the measurement window, not a record-loss rate or proof that every consumer caught up. It can slightly exceed 100% when operations cross a window boundary; n/a means no writes completed. A completed load step is not a health or capacity pass. Peak production is a short-window observation, not sustained capacity.

One fresh JVM per mode; record bytes 1024, 4096-record batches, 2s warmup and 10s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

| Mode | Highest unique writes/s | Consumers at that rate | Delivery coverage at that rate | Why the test stopped |
| --- | ---: | ---: | ---: | --- |
| IN_MEMORY | 1,338,382 | 2 | 99.0% | Broker/client limit reached (BROKER_OR_CLIENT_LIMIT) |
| FILE | 233,071 | 8 | 16.6% | Broker/client limit reached (BROKER_OR_CLIENT_LIMIT) |

**IN_MEMORY: delivery progress**

The last measured stage (9, 256 consumers) covered **n/a** of its delivery demand. The test stopped at stage 9 with 256 producers and 256 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 1,026,715 | 1,026,715 | 1,026,315 | 100.0% |
| 2 | 2 | 1,338,382 | 2,676,764 | 2,650,149 | 99.0% |
| 3 | 4 | 764,816 | 3,059,264 | 2,983,700 | 97.5% |
| 4 | 8 | 345,961 | 2,767,688 | 2,594,709 | 93.8% |
| 5 | 16 | 142,333 | 2,277,328 | 2,000,324 | 87.8% |
| 6 | 32 | 88,752 | 2,840,064 | 1,804,631 | 63.5% |
| 7 | 64 | 76,858 | 4,918,912 | 1,763,636 | 35.9% |
| 8 | 128 | 50,884 | 6,513,152 | 1,605,452 | 24.6% |
| 9 | 256 | 0 | 0 | 0 | n/a |

**IN_MEMORY: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 10.23 | 16.7% | 6750.0 | 33.554 | Measured |
| 2 | 10.16 | 38.2% | 5694.0 | 16.777 | Measured |
| 3 | 10.25 | 54.5% | 1950.0 | 16.777 | Measured |
| 4 | 10.02 | 65.9% | 1106.0 | 67.109 | Measured |
| 5 | 10.16 | 68.3% | 1796.0 | 134.218 | Measured |
| 6 | 10.25 | 66.8% | 2382.0 | 134.218 | Measured |
| 7 | 10.02 | 66.9% | 5526.0 | 268.435 | Measured |
| 8 | 10.22 | 51.7% | 10030.0 | 1073.742 | Measured |
| 9 | 0.26 | 0.0% | 0.0 | 1073.742 | Broker/client limit reached |

IN_MEMORY: JDK 26.0.2.1; Windows_11; filesystem NTFS; CPU percentages use 12 cores as 100%; 10230 MiB maximum heap.
Free-disk reserve: 119.61 GiB (sampled).
Admission budgets: requests 1278.75 MiB, responses 1278.75 MiB; fetch payload 4 MiB; retained log 2047 MiB.

**FILE: delivery progress**

The last measured stage (7, 64 consumers) covered **8.0%** of its delivery demand. The test stopped at stage 7 with 64 producers and 64 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 159,709 | 159,709 | 159,709 | 100.0% |
| 2 | 2 | 175,162 | 350,324 | 349,524 | 99.8% |
| 3 | 4 | 185,623 | 742,492 | 285,079 | 38.4% |
| 4 | 8 | 233,071 | 1,864,568 | 309,685 | 16.6% |
| 5 | 16 | 125,255 | 2,004,080 | 226,770 | 11.3% |
| 6 | 32 | 85,286 | 2,729,152 | 250,578 | 9.2% |
| 7 | 64 | 111,524 | 7,137,536 | 568,029 | 8.0% |

**FILE: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 10.21 | 11.8% | 332.0 | 67.109 | Measured |
| 2 | 10.24 | 16.4% | 1660.0 | 134.218 | Measured |
| 3 | 10.17 | 14.4% | 2418.0 | 268.435 | Measured |
| 4 | 10.16 | 18.1% | 6970.0 | 536.871 | Measured |
| 5 | 10.01 | 64.5% | 6166.0 | 2147.484 | Measured |
| 6 | 10.09 | 69.6% | 5046.0 | 4294.967 | Measured |
| 7 | 5.51 | 73.0% | 6292.0 | 4294.967 | Broker/client limit reached |

FILE: JDK 26.0.2.1; Windows_11; filesystem NTFS; CPU percentages use 12 cores as 100%; 10230 MiB maximum heap.
Free-disk reserve: 119.61 GiB (sampled).
Admission budgets: requests 1278.75 MiB, responses 1278.75 MiB; fetch payload 4 MiB; retained log 2047 MiB.
<!-- benchmark-contributor-pc:end -->

The [mandatory contributor pre-push check](scripts/README.md#contributor-pc-check-before-every-push) attaches local performance evidence. CI validates the attachment and retains it with the pipeline artifacts; default-branch publication refreshes the block above independently of the CI measurements.

## Help and contributions

For setup help or a reproducible bug, start with [support and troubleshooting](SUPPORT.md). Use the [issue forms](https://github.com/RishabhRathi-Dev/Setu/issues/new/choose) for bugs and feature requests. Report vulnerabilities privately following the [security policy](SECURITY.md).

Contributions should include focused changes, relevant verification, and updated documentation. The [contributing guide](CONTRIBUTING.md) covers setup, checks, and pull requests; [AGENTS.md](AGENTS.md) records the repository map and contracts for coding agents.
