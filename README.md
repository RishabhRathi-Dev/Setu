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
[![in-memory: 500.00K/s target, 499.99K/s CI, 100% qualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40952170823)
[![file: 250.00K/s target, 117.86K/s CI, 47% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40952170823)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest CI target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 0.79 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40952170823)
[![memory resources: 59% CPU, 256 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40952170823)
[![memory JFR: 1.65 GiB/s alloc, 10.2 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40952170823)
[![file p99: 16416.51 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40952170823)
[![file resources: 23% CPU, 126 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40952170823)
[![file JFR: 0.50 GiB/s alloc, 6.8 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40952170823)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `35a1e6d045814984e5e1729956541de278226e01`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40952170823).

Runner: AMD EPYC 9V74 80-Core Processor; 4.0 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

Runner: AMD EPYC 7763 64-Core Processor; 4.0 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

Writes count unique records; each record needs one delivery in this workload. Write target achieved compares actual writes with requested writes. Delivery coverage compares unique deliveries with completed writes in the same window. Similar write and delivery rates do not guarantee the requested load or other limits were met.

| Mode | Reference writes/s | Median actual writes/s | Write target achieved | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 499,994 | 100.0% | 0.788 | 256 | 59.0000 | True |
| FILE | 250,000 | 117,862 | 47.1% | 16416.506 | 126 | 22.5657 | False |

**Fixed-target traffic**

| Mode | Repeat | Requested writes/s | Actual writes/s | Unique deliveries/s | Write target achieved | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 125,000 | 92,339 | 92,356 | 73.9% | 100.0% |
| FILE | 1 | 200,000 | 124,894 | 124,211 | 62.4% | 99.5% |
| FILE | 1 | 250,000 | 117,862 | 110,967 | 47.1% | 94.2% |
| FILE | 2 | 125,000 | 113,161 | 113,852 | 90.5% | 100.6% |
| FILE | 2 | 200,000 | 104,311 | 107,290 | 52.2% | 102.9% |
| FILE | 2 | 250,000 | 115,849 | 115,849 | 46.3% | 100.0% |
| FILE | 3 | 125,000 | 118,741 | 115,703 | 95.0% | 97.4% |
| FILE | 3 | 200,000 | 138,786 | 126,618 | 69.4% | 91.2% |
| FILE | 3 | 250,000 | 129,792 | 121,003 | 51.9% | 93.2% |
| IN_MEMORY | 1 | 250,000 | 249,993 | 250,018 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 400,000 | 400,000 | 400,009 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 500,000 | 499,994 | 500,002 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 250,000 | 249,993 | 250,061 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 400,000 | 400,000 | 400,000 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 500,000 | 499,994 | 500,002 | 100.0% | 100.0% |
| IN_MEMORY | 3 | 250,000 | 249,993 | 250,010 | 100.0% | 100.0% |
| IN_MEMORY | 3 | 400,000 | 400,000 | 400,000 | 100.0% | 100.0% |
| IN_MEMORY | 3 | 500,000 | 499,994 | 500,002 | 100.0% | 100.0% |

**Fixed-target latency and resource checks**

| Mode | Repeat | Load | p99 ms | CPU % | Heap slope MiB/s | Qualified window |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 7499.416 | 18.5075 | 0.281 | False |
| FILE | 1 | 80% | 11391.730 | 19.6914 | 0.292 | False |
| FILE | 1 | 100% | 16416.506 | 18.1909 | 0.044 | False |
| FILE | 2 | 50% | 3089.105 | 22.5657 | -0.030 | False |
| FILE | 2 | 80% | 14302.577 | 16.6494 | 0.042 | False |
| FILE | 2 | 100% | 15846.081 | 19.6158 | 0.067 | False |
| FILE | 3 | 50% | 3600.810 | 19.6412 | -0.041 | False |
| FILE | 3 | 80% | 9177.137 | 20.0326 | 0.072 | False |
| FILE | 3 | 100% | 15216.935 | 18.9079 | 0.241 | False |
| IN_MEMORY | 1 | 50% | 0.592 | 42.9070 | 0.159 | True |
| IN_MEMORY | 1 | 80% | 0.660 | 52.8321 | 0.174 | True |
| IN_MEMORY | 1 | 100% | 0.713 | 58.1072 | 0.293 | True |
| IN_MEMORY | 2 | 50% | 0.582 | 43.0151 | 0.042 | True |
| IN_MEMORY | 2 | 80% | 0.690 | 52.6332 | 0.118 | True |
| IN_MEMORY | 2 | 100% | 0.788 | 58.2990 | 0.545 | True |
| IN_MEMORY | 3 | 50% | 0.621 | 43.4999 | 0.324 | True |
| IN_MEMORY | 3 | 80% | 0.643 | 53.2325 | -0.153 | True |
| IN_MEMORY | 3 | 100% | 0.732 | 59.0000 | 0.314 | True |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | throughput 92,339/s <95% of offered 125,000/s; p99 7499.416 ms outside [0,10) ms |
| FILE | 1 | 80% | throughput 124,894/s <95% of offered 200,000/s; p99 11391.730 ms outside [0,100) ms |
| FILE | 1 | 100% | consumption 94.2% <95%; throughput 117,862/s <95% of offered 250,000/s; p99 16416.506 ms outside [0,100) ms |
| FILE | 2 | 50% | throughput 113,161/s <95% of offered 125,000/s; p99 3089.105 ms outside [0,10) ms |
| FILE | 2 | 80% | throughput 104,311/s <95% of offered 200,000/s; p99 14302.577 ms outside [0,100) ms |
| FILE | 2 | 100% | throughput 115,849/s <95% of offered 250,000/s; p99 15846.081 ms outside [0,100) ms |
| FILE | 3 | 50% | throughput 118,741/s <95% of offered 125,000/s; p99 3600.810 ms outside [0,10) ms |
| FILE | 3 | 80% | consumption 91.2% <95%; throughput 138,786/s <95% of offered 200,000/s; p99 9177.137 ms outside [0,100) ms |
| FILE | 3 | 100% | consumption 93.2% <95%; throughput 129,792/s <95% of offered 250,000/s; p99 15216.935 ms outside [0,100) ms |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 64.094 | 0.622 | 58.360 | 7499.416 |
| FILE | 1 | 80% | 28.443 | 0.877 | 27.329 | 11391.730 |
| FILE | 1 | 100% | 35.815 | 0.754 | 32.932 | 16416.506 |
| FILE | 2 | 50% | 67.961 | 0.666 | 62.128 | 3089.105 |
| FILE | 2 | 80% | 67.764 | 1.034 | 63.078 | 14302.577 |
| FILE | 2 | 100% | 36.274 | 0.684 | 32.260 | 15846.081 |
| FILE | 3 | 50% | 39.322 | 0.966 | 39.682 | 3600.810 |
| FILE | 3 | 80% | 31.687 | 1.112 | 31.834 | 9177.137 |
| FILE | 3 | 100% | 31.179 | 0.767 | 31.293 | 15216.935 |
| IN_MEMORY | 1 | 50% | 0.437 | 0.330 | 0.194 | 0.592 |
| IN_MEMORY | 1 | 80% | 0.445 | 0.379 | 0.265 | 0.660 |
| IN_MEMORY | 1 | 100% | 0.483 | 0.427 | 0.284 | 0.713 |
| IN_MEMORY | 2 | 50% | 0.449 | 0.359 | 0.190 | 0.582 |
| IN_MEMORY | 2 | 80% | 0.456 | 0.411 | 0.267 | 0.690 |
| IN_MEMORY | 2 | 100% | 0.512 | 0.461 | 0.287 | 0.788 |
| IN_MEMORY | 3 | 50% | 0.469 | 0.407 | 0.207 | 0.621 |
| IN_MEMORY | 3 | 80% | 0.459 | 0.440 | 0.277 | 0.643 |
| IN_MEMORY | 3 | 100% | 0.508 | 0.476 | 0.292 | 0.732 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 18306 | 70200.064094 | 48.037887 | 350.120267 |
| FILE | 1 | 2 | 24459 | 70545.808827 | 26.607615 | 205.180992 |
| FILE | 1 | 3 | 22633 | 69288.435602 | 28.327935 | 416.989478 |
| FILE | 2 | 1 | 21825 | 57256.458597 | 38.895615 | 279.039884 |
| FILE | 2 | 2 | 20056 | 70867.698034 | 54.231039 | 270.139800 |
| FILE | 2 | 3 | 22757 | 70290.833752 | 29.491199 | 215.997344 |
| FILE | 3 | 1 | 23179 | 70483.717639 | 31.866879 | 351.069417 |
| FILE | 3 | 2 | 25915 | 68823.427791 | 28.278783 | 211.834533 |
| FILE | 3 | 3 | 24381 | 70577.022969 | 25.772031 | 455.322304 |
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
Source revision `35a1e6d045814984e5e1729956541de278226e01`. [CI redline report and captures](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40952170823)

Resource-enforced redline diagnostics (unqualified)

Source revision `35a1e6d045814984e5e1729956541de278226e01`; enforcement: systemd-cgroup-v2.

Host: AMD EPYC 9V74 80-Core Processor; filesystem/media: /dev/nvme0n1p1 ext4 (non-rotational). Allocation: 2 CPU cores, 6554 MiB process-tree memory; 512 MiB worker heap, 128 MiB direct memory; free-disk reserve 17.03 GiB.

Execution exit code: 0.

**Redline diagnostics: find a runtime limit (unqualified)**

**Unique writes/s** counts records appended once. This is a fanout workload: every consumer reads the same partition, so one write creates one delivery per consumer. **Deliveries needed/s = unique writes/s × consumers.** **Delivery coverage** is completed deliveries divided by (written records × consumers) within the same window.

For example, 100,000 writes/s with 8 consumers needs 800,000 deliveries/s. 400,000 completed deliveries/s covers 50% of that demand.

Coverage describes aggregate progress during the measurement window, not a record-loss rate or proof that every consumer caught up. It can slightly exceed 100% when operations cross a window boundary; n/a means no writes completed. A completed load step is not a health or capacity pass. Peak production is a short-window observation, not sustained capacity.

One fresh JVM per mode; record bytes 1024, 256-record batches, 2s warmup and 2s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

| Mode | Highest unique writes/s | Consumers at that rate | Delivery coverage at that rate | Why the test stopped |
| --- | ---: | ---: | ---: | --- |
| FILE | 114,474 | 64 | 5.3% | Broker/client limit reached (BROKER_OR_CLIENT_LIMIT) |

**FILE: delivery progress**

The last measured stage (9, 256 consumers) covered **0.9%** of its delivery demand. The test stopped at stage 9 with 256 producers and 256 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 35,513 | 35,513 | 35,385 | 99.6% |
| 2 | 2 | 48,168 | 96,336 | 77,299 | 80.2% |
| 3 | 4 | 96,933 | 387,732 | 222,090 | 57.3% |
| 4 | 8 | 75,609 | 604,872 | 200,134 | 33.1% |
| 5 | 16 | 58,499 | 935,984 | 198,872 | 21.2% |
| 6 | 32 | 113,139 | 3,620,448 | 358,167 | 9.9% |
| 7 | 64 | 114,474 | 7,326,336 | 390,641 | 5.3% |
| 8 | 128 | 113,242 | 14,494,976 | 260,873 | 1.8% |
| 9 | 256 | 58,151 | 14,886,656 | 137,486 | 0.9% |

**FILE: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 2.00 | 62.2% | 312.0 | 134.218 | Measured |
| 2 | 2.00 | 40.2% | 154.0 | 134.218 | Measured |
| 3 | 2.00 | 65.4% | 360.0 | 134.218 | Measured |
| 4 | 2.00 | 40.9% | 396.0 | 134.218 | Measured |
| 5 | 2.00 | 37.7% | 164.0 | 268.435 | Measured |
| 6 | 2.01 | 47.1% | 358.0 | 268.435 | Measured |
| 7 | 2.01 | 59.1% | 366.0 | 268.435 | Measured |
| 8 | 2.03 | 94.9% | 468.0 | 536.871 | Measured |
| 9 | 2.37 | 95.7% | 502.0 | 2147.484 | Broker/client limit reached |

FILE: JDK 26.0.2.1; Linux; filesystem ext4; CPU percentages use 2 cores as 100%; 512 MiB maximum heap.


Resource-enforced redline diagnostics (unqualified)

Source revision `35a1e6d045814984e5e1729956541de278226e01`; enforcement: systemd-cgroup-v2.

Host: AMD EPYC 7763 64-Core Processor; filesystem/media: /dev/sda1 ext4 (rotational). Allocation: 2 CPU cores, 6554 MiB process-tree memory; 512 MiB worker heap, 128 MiB direct memory; free-disk reserve 17.03 GiB.

Execution exit code: 0.

**Redline diagnostics: find a runtime limit (unqualified)**

**Unique writes/s** counts records appended once. This is a fanout workload: every consumer reads the same partition, so one write creates one delivery per consumer. **Deliveries needed/s = unique writes/s × consumers.** **Delivery coverage** is completed deliveries divided by (written records × consumers) within the same window.

For example, 100,000 writes/s with 8 consumers needs 800,000 deliveries/s. 400,000 completed deliveries/s covers 50% of that demand.

Coverage describes aggregate progress during the measurement window, not a record-loss rate or proof that every consumer caught up. It can slightly exceed 100% when operations cross a window boundary; n/a means no writes completed. A completed load step is not a health or capacity pass. Peak production is a short-window observation, not sustained capacity.

One fresh JVM per mode; record bytes 1024, 256-record batches, 2s warmup and 2s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

| Mode | Highest unique writes/s | Consumers at that rate | Delivery coverage at that rate | Why the test stopped |
| --- | ---: | ---: | ---: | --- |
| IN_MEMORY | 271,264 | 1 | 94.4% | JVM heap exhausted (JVM_OOM) |

**IN_MEMORY: delivery progress**

The last measured stage (11, 1024 consumers) covered **15.0%** of its delivery demand. The test stopped at stage 12 with 2048 producers and 2048 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 271,264 | 271,264 | 255,945 | 94.4% |
| 2 | 2 | 256,590 | 513,180 | 483,060 | 94.1% |
| 3 | 4 | 191,523 | 766,092 | 704,528 | 92.0% |
| 4 | 8 | 114,951 | 919,608 | 801,382 | 87.1% |
| 5 | 16 | 71,286 | 1,140,576 | 1,007,360 | 88.3% |
| 6 | 32 | 47,292 | 1,513,344 | 1,182,058 | 78.1% |
| 7 | 64 | 27,065 | 1,732,160 | 1,200,040 | 69.3% |
| 8 | 128 | 24,220 | 3,100,160 | 896,273 | 28.9% |
| 9 | 256 | 23,812 | 6,095,872 | 792,967 | 13.0% |
| 10 | 512 | 20,689 | 10,592,768 | 718,010 | 6.8% |
| 11 | 1024 | 2,812 | 2,879,488 | 431,868 | 15.0% |

**IN_MEMORY: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 2.01 | 94.4% | 218.0 | 8.389 | Measured |
| 2 | 2.01 | 96.2% | 230.0 | 8.389 | Measured |
| 3 | 2.03 | 95.0% | 304.0 | 33.554 | Measured |
| 4 | 2.03 | 95.5% | 336.0 | 67.109 | Measured |
| 5 | 2.03 | 97.4% | 336.0 | 33.554 | Measured |
| 6 | 2.01 | 98.5% | 396.0 | 67.109 | Measured |
| 7 | 2.01 | 98.6% | 358.0 | 67.109 | Measured |
| 8 | 2.02 | 94.2% | 236.0 | 134.218 | Measured |
| 9 | 2.07 | 95.3% | 294.0 | 134.218 | Measured |
| 10 | 2.02 | 95.1% | 512.0 | 536.871 | Measured |
| 11 | 11.65 | 93.0% | 502.0 | 1073.742 | Measured |

IN_MEMORY: JDK 26.0.2.1; Linux; filesystem ext4; CPU percentages use 2 cores as 100%; 512 MiB maximum heap.

<!-- benchmark-redline:end -->

### Contributor PC benchmarks

<!-- benchmark-contributor-pc:start -->
Latest contributor PC run (diagnostic, unqualified)

[![memory writes (contributor PC): 500.00K/s target, 499.79K/s actual, 100%, unqualified](benchmark-badges/contributor-memory-throughput.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40952170823)
[![memory p99 (contributor PC): 14.68 ms at target](benchmark-badges/contributor-memory-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40952170823)
[![memory resources (contributor PC): 24.3% CPU, 498 MiB heap](benchmark-badges/contributor-memory-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40952170823)
[![memory JFR (contributor PC): 1.68 GiB/s alloc, 1.1 ms GC](benchmark-badges/contributor-memory-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40952170823)
[![file writes (contributor PC): 250.00K/s target, 48.50K/s actual, 19%, unqualified](benchmark-badges/contributor-file-throughput.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40952170823)
[![file p99 (contributor PC): 8002.73 ms at target](benchmark-badges/contributor-file-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40952170823)
[![file resources (contributor PC): 31.5% CPU, 466 MiB heap](benchmark-badges/contributor-file-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40952170823)
[![file JFR (contributor PC): 0.23 GiB/s alloc, 0.4 ms GC](benchmark-badges/contributor-file-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40952170823)

[Report, logs and downloadable results](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40952170823)

Contributor: Rishabh Rathi; finished 2026-10-29T20:22:07.396396+00:00; revision `d0fa4423bb81e846eec74e6346152226ede5d526`.

Windows-11-10.0.26100-SP0; AMD Ryzen 5 7600X 6-Core Processor; 8 JVM active processors; 512 MiB heap; 128 MiB direct-memory limit; no OS resource quota.

JDK: openjdk version "26.0.2.1" 2026-08-18 OpenJDK Runtime Environment Temurin-26.0.2.1+1 (build 26.0.2.1+1) OpenJDK 64-Bit Server VM Temurin-26.0.2.1+1 (build 26.0.2.1+1, mixed mode, sharing). Media: Filesystem per worker; physical media not detected.

Two fresh JVMs per mode; 1 KiB records; 256-record batches; 5s minimum adaptive warmup and 10s measurement at each load. Broker and clients share the PC. These results are contributor supplied and do not qualify sustained capacity.

**Contributor PC summary (diagnostic, unqualified)**

CI-style summary: median actual writes/s across the two full-target windows; worst p99 at that target. Heap, average CPU, allocation rate and GC pause values are maxima across all fixed-target windows. Window checks may pass while these contributor measurements remain unqualified.

| Mode | Reference writes/s | Median actual writes/s | Write target achieved | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Window checks | Status |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| IN_MEMORY | 500,000 | 499,789 | 100.0% | 14.680 | 498.0 | 24.2944 | Missed | Unqualified |
| FILE | 250,000 | 48,499 | 19.4% | 8002.732 | 466.0 | 31.4986 | Missed | Unqualified |

**Fixed target: requested writes versus completed work**

These windows count unique records, with one delivery per record. Requested writes/s is the offered load; actual writes/s is the completed rate. Write target achieved compares actual with requested writes. Delivery coverage compares unique deliveries with completed writes. Consumers keeping up does not mean the write target was met.

| Mode | Repeat | Requested writes/s | Actual writes/s | Unique deliveries/s | Write target achieved | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| IN_MEMORY | 1 | 250,000 | 249,984 | 250,112 | 100.0% | 100.1% |
| IN_MEMORY | 1 | 400,000 | 400,000 | 400,026 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 500,000 | 499,635 | 499,558 | 99.9% | 100.0% |
| FILE | 1 | 125,000 | 51,200 | 51,251 | 41.0% | 100.1% |
| FILE | 1 | 200,000 | 46,054 | 46,080 | 23.0% | 100.1% |
| FILE | 1 | 250,000 | 47,923 | 47,923 | 19.2% | 100.0% |
| IN_MEMORY | 2 | 250,000 | 249,984 | 249,882 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 400,000 | 399,846 | 399,898 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 500,000 | 499,942 | 499,994 | 100.0% | 100.0% |
| FILE | 2 | 125,000 | 49,971 | 49,997 | 40.0% | 100.1% |
| FILE | 2 | 200,000 | 49,050 | 49,101 | 24.5% | 100.1% |
| FILE | 2 | 250,000 | 49,075 | 49,101 | 19.6% | 100.1% |

**Fixed target: latency and resource checks**

Window checks include throughput, consumption, latency, CPU and heap limits. A passed window remains a local diagnostic, not qualified sustained capacity.

| Mode | Repeat | Load | p99 ms | CPU budget used | Force p99 ms | Window checks |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 1 | 50% | 11.731 | 7.6% | 0.000 | Missed |
| IN_MEMORY | 1 | 80% | 11.239 | 15.5% | 0.000 | Passed |
| IN_MEMORY | 1 | 100% | 14.680 | 21.9% | 0.000 | Passed |
| FILE | 1 | 50% | 5821.694 | 29.6% | 6.943 | Missed |
| FILE | 1 | 80% | 7587.496 | 30.3% | 7.655 | Missed |
| FILE | 1 | 100% | 8002.732 | 29.7% | 7.143 | Missed |
| IN_MEMORY | 2 | 50% | 11.837 | 10.4% | 0.000 | Missed |
| IN_MEMORY | 2 | 80% | 10.125 | 15.0% | 0.000 | Passed |
| IN_MEMORY | 2 | 100% | 10.510 | 24.3% | 0.000 | Missed |
| FILE | 2 | 50% | 5934.940 | 31.5% | 7.361 | Missed |
| FILE | 2 | 80% | 7444.890 | 30.4% | 7.528 | Missed |
| FILE | 2 | 100% | 7914.652 | 29.6% | 7.332 | Missed |

**Redline diagnostics: find a runtime limit (unqualified)**

**Unique writes/s** counts records appended once. This is a fanout workload: every consumer reads the same partition, so one write creates one delivery per consumer. **Deliveries needed/s = unique writes/s × consumers.** **Delivery coverage** is completed deliveries divided by (written records × consumers) within the same window.

For example, 100,000 writes/s with 8 consumers needs 800,000 deliveries/s. 400,000 completed deliveries/s covers 50% of that demand.

Coverage describes aggregate progress during the measurement window, not a record-loss rate or proof that every consumer caught up. It can slightly exceed 100% when operations cross a window boundary; n/a means no writes completed. A completed load step is not a health or capacity pass. Peak production is a short-window observation, not sustained capacity.

One fresh JVM per mode; record bytes 1024, 256-record batches, 2s warmup and 2s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

| Mode | Highest unique writes/s | Consumers at that rate | Delivery coverage at that rate | Why the test stopped |
| --- | ---: | ---: | ---: | --- |
| IN_MEMORY | 972,837 | 1 | 98.5% | JVM heap exhausted (JVM_OOM) |
| FILE | 319,701 | 128 | 2.2% | Broker/client limit reached (BROKER_OR_CLIENT_LIMIT): QUOTA_EXCEEDED |

**IN_MEMORY: delivery progress**

The last measured stage (10, 512 consumers) covered **6.3%** of its delivery demand. The test stopped at stage 11 with 1024 producers and 1024 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 972,837 | 972,837 | 957,890 | 98.5% |
| 2 | 2 | 873,657 | 1,747,314 | 1,717,115 | 98.3% |
| 3 | 4 | 707,788 | 2,831,152 | 2,770,738 | 97.9% |
| 4 | 8 | 441,062 | 3,528,496 | 3,490,134 | 98.9% |
| 5 | 16 | 241,162 | 3,858,592 | 3,723,821 | 96.5% |
| 6 | 32 | 146,158 | 4,677,056 | 4,213,909 | 90.1% |
| 7 | 64 | 69,737 | 4,463,168 | 3,285,235 | 73.6% |
| 8 | 128 | 26,604 | 3,405,312 | 2,770,992 | 81.4% |
| 9 | 256 | 13,965 | 3,575,040 | 2,620,764 | 73.3% |
| 10 | 512 | 29,125 | 14,912,000 | 935,329 | 6.3% |

**IN_MEMORY: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 2.04 | 42.2% | 372.0 | 0.524 | Measured |
| 2 | 2.09 | 54.5% | 386.0 | 1.049 | Measured |
| 3 | 2.08 | 93.0% | 318.0 | 1.049 | Measured |
| 4 | 2.10 | 99.8% | 466.0 | 4.194 | Measured |
| 5 | 2.10 | 99.7% | 398.0 | 4.194 | Measured |
| 6 | 2.09 | 97.6% | 258.0 | 8.389 | Measured |
| 7 | 2.05 | 93.4% | 300.0 | 16.777 | Measured |
| 8 | 11.27 | 95.1% | 268.0 | 33.554 | Measured |
| 9 | 11.26 | 98.9% | 428.0 | 67.109 | Measured |
| 10 | 2.15 | 62.3% | 494.0 | 268.435 | Measured |

IN_MEMORY: JDK 26.0.2.1; Windows_11; filesystem NTFS; CPU percentages use 8 cores as 100%; 512 MiB maximum heap.
Free-disk reserve: 125.90 GiB (sampled).

**FILE: delivery progress**

The last measured stage (9, 256 consumers) covered **0.3%** of its delivery demand. The test stopped at stage 9 with 256 producers and 256 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 97,102 | 97,102 | 95,979 | 98.8% |
| 2 | 2 | 93,024 | 186,048 | 185,804 | 99.9% |
| 3 | 4 | 124,162 | 496,648 | 401,008 | 80.7% |
| 4 | 8 | 166,494 | 1,331,952 | 384,654 | 28.9% |
| 5 | 16 | 172,550 | 2,760,800 | 587,511 | 21.3% |
| 6 | 32 | 203,494 | 6,511,808 | 1,041,023 | 16.0% |
| 7 | 64 | 254,983 | 16,318,912 | 1,422,930 | 8.7% |
| 8 | 128 | 319,701 | 40,921,728 | 887,608 | 2.2% |
| 9 | 256 | 291,753 | 74,688,768 | 245,400 | 0.3% |

**FILE: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 2.05 | 21.2% | 272.0 | 4.194 | Measured |
| 2 | 2.10 | 26.5% | 106.0 | 16.777 | Measured |
| 3 | 2.11 | 38.8% | 286.0 | 16.777 | Measured |
| 4 | 2.12 | 35.3% | 390.0 | 33.554 | Measured |
| 5 | 2.07 | 36.3% | 186.0 | 67.109 | Measured |
| 6 | 2.09 | 55.3% | 150.0 | 134.218 | Measured |
| 7 | 2.12 | 85.4% | 306.0 | 134.218 | Measured |
| 8 | 2.09 | 89.4% | 396.0 | 268.435 | Measured |
| 9 | 0.28 | 0.0% | 0.0 | 268.435 | Broker/client limit reached |

FILE: JDK 26.0.2.1; Windows_11; filesystem NTFS; CPU percentages use 8 cores as 100%; 512 MiB maximum heap.
Free-disk reserve: 125.90 GiB (sampled).
<!-- benchmark-contributor-pc:end -->

The [mandatory contributor pre-push check](scripts/README.md#contributor-pc-check-before-every-push) attaches local performance evidence. CI validates the attachment and retains it with the pipeline artifacts; default-branch publication refreshes the block above independently of the CI measurements.

## Help and contributions

For setup help or a reproducible bug, start with [support and troubleshooting](SUPPORT.md). Use the [issue forms](https://github.com/RishabhRathi-Dev/Setu/issues/new/choose) for bugs and feature requests. Report vulnerabilities privately following the [security policy](SECURITY.md).

Contributions should include focused changes, relevant verification, and updated documentation. The [contributing guide](CONTRIBUTING.md) covers setup, checks, and pull requests; [AGENTS.md](AGENTS.md) records the repository map and contracts for coding agents.
