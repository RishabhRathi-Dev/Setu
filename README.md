# Setu

**A single-node Java message broker with partitioned logs and durable acknowledgements.**

[![CI](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml/badge.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![Java 26](https://img.shields.io/badge/Java-26-2563eb)](pom.xml)
[![Maven Wrapper](https://img.shields.io/badge/build-Maven_Wrapper-2563eb)](.mvn/wrapper/maven-wrapper.properties)

[Quick start](#quick-start) · [Documentation](docs/README.md) · [Examples](examples/README.md) · [Contributing](CONTRIBUTING.md) · [Get help](SUPPORT.md)

Setu stores ordered records in topic partitions and exposes explicit produce, fetch, and consumer-progress operations. The default `FILE` engine uses OpenHFT Chronicle Queue; `IN_MEMORY` provides bounded, volatile storage. The implementation includes blocking and selector TCP transports, synchronous and bounded async Java clients, managed groups, identified producer retries, atomic consume-and-produce across one input and one output partition, and an embedded mTLS security pipeline.

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
- [When to use Setu](#when-to-use-setu)
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

Both launchers load [config/jvm.options](config/jvm.options), including ZGC, string deduplication, and Chronicle's module/native access. [Operations](docs/operations.md) covers direct JAR launches, logging, shutdown, and recovery.

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

### 3. Choose client concurrency

`SetuClient` handles one exchange at a time **per instance/connection**. Use it for sequential workflows, or give independent workers their own instances. For concurrent submissions through one shared client API, use `AsyncSetuClient` with a bounded connection pool and queue. A long-poll fetch occupies one connection until it returns; give polling consumers separate connections or a separate pool from producers.

Run the [async producer example](examples/SetuAsyncProducer.java) against the same broker:

```powershell
java '@config/jvm.options' -cp 'setu-server/target/lib/*' examples/SetuAsyncProducer.java
```

It submits four partition writes before waiting, bounds outstanding work, checks every broker response, and verifies all 128 records. It keeps one unresolved write per partition to preserve application order. Pooling alone does not preserve submission order across connections, and ordinary writes are never automatically retried. See [choosing a client](setu-client/README.md#choosing-a-client) for sizing, backpressure and retry rules.

## When to use Setu

Setu fits embedded or single-node messaging where applications can accept broker downtime and manage recovery from local durable storage. Select `FILE` for forced durable acknowledgements, or `IN_MEMORY` for bounded volatile data. Measure the intended workload on the deployment host before choosing capacity limits.

FILE transactions can atomically publish a batch to one output partition and advance one fetched input partition's offset, **including when input and output belong to different topics**. Atomic output to multiple partitions or multiple topics is not supported. Broker transactions do not include external database/API effects; see [transaction boundaries](docs/transactions.md#transaction-scope).

Deployments requiring continued service after node loss, replicated durability or automatic broker failover need capabilities Setu does not implement. Monitoring and process restart help operations but do not add replication. The [implementation boundaries](#implementation-boundaries) describe the remaining limits.

## Architecture

```mermaid
flowchart LR
    app[Java application] --> client["SetuClient or AsyncSetuClient"]
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
| Primary admission | Configurable; defaults to 32 MiB / 1,024 outstanding writes per partition | Shared 256 MiB accounted retained-log budget |

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

CI also runs Windows functional tests and [a source security gate](.github/workflows/security.yml). Benchmark-helper tests, bounded chaos, load checks, and baseline/target measurements are covered by [CI](.github/workflows/ci.yml). The root Maven reactor includes benchmark workloads; use the commands above for ordinary functional verification. [Contributing](CONTRIBUTING.md) documents the broader verification matrix and repository conventions.

## Repository guide

| Path | Responsibility / documentation |
| --- | --- |
| [setu-core/](setu-core/README.md) | Broker API, topics, partitions, membership, and protocol codec |
| [setu-storage/](setu-storage/README.md) | Partition logs, group commit, recovery, and migration |
| [setu-server/](setu-server/README.md) | TCP transports, secure dispatch, and standalone launcher |
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
- FILE brokers offer opt-in managed assignment/rebalancing, producer deduplication and single-input/single-output-partition transactions, including across different input/output topics. Multi-output-partition transactions are unsupported. Producer sequencing and external effects still require application coordination; see [transaction guarantees and limits](docs/transactions.md).
- New durable documents carry CRC32C checksums; older documents remain readable without checksum protection. Per-request `DURABLE` (default) and weaker `ACCEPTED` acknowledgement modes are available.
- `SetuClient` serializes calls per instance/connection; independent instances can run concurrently. `AsyncSetuClient` adds bounded pooling, keyed routing, reconnects and retries for reads and identified transactional/idempotent writes. Ordinary writes retain uncertain retry outcomes. See [client selection and ordering](setu-client/README.md#choosing-a-client).
- Metadata checkpoints compact historical transaction payloads while preserving fencing and progress. They pause broker requests; producer/group deletion and incremental snapshots remain unsupported. See [checkpoint recovery and compatibility](docs/journal-checkpoints.md).
- ACL grants and quotas are process-local; persistent policy administration and automatic certificate rotation are not implemented.

The [PostgreSQL outbox/inbox adapter](docs/postgres-outbox.md) supports transactional publication intent and deduplicated database effects. Outbox publication may repeat; its PostgreSQL transaction is separate from Setu's broker transaction boundary.

## Performance

Reference targets are **500,000 records/s in memory** and **250,000 records/s durable**, with 1 KiB records. These are qualification goals. A completed run must also satisfy consumption, latency, CPU, heap, and error criteria. A smoke run or an unqualified result does not establish sustained capacity.

The [benchmark guide](setu-benchmark/README.md) documents reproducible profiles, strict/report-only policies, host budgets, and JFR interpretation. Results from a different storage engine do not qualify Chronicle Queue.

The generated blocks below retain their source revision, host information, and qualification status. Read them against that revision rather than as measurements of the current checkout; CI refreshes them after new runs.

A result's target percentage describes that workload and runner, not a universal limit for HDD, SSD or NVMe storage. Check the **matching storage mode's** host/filesystem in the [machine-readable snapshot](benchmark-badges/node-target-results.json); the FILE and IN_MEMORY jobs can run on different hosts. Virtual device names and rotational flags do not independently establish the underlying physical media. A previous qualified run does not qualify a later revision or another deployment, and cross-host changes alone cannot establish a software regression. Headline p99 includes scheduled-completion backlog; use the separate produce/fetch/acknowledgement and force metrics to investigate the cause.

### CI benchmarks

The badges and expandable report below come from resource-enforced CI runs. Optional contributor PC diagnostics follow in their own section. CI publication continues when contributor evidence is absent or fails validation; invalid evidence still reports its failure separately.

<!-- benchmark-metrics-badges:start -->
[![in-memory: 500.00K/s target, 499.99K/s CI, 100% unqualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/42004336938)
[![file: 250.00K/s target, 165.21K/s CI, 66% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/42004336938)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest CI target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 195.69 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/42004336938)
[![memory resources: 62% CPU, 254 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/42004336938)
[![memory JFR: 1.66 GiB/s alloc, 37.1 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/42004336938)
[![file p99: 11182.01 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/42004336938)
[![file resources: 60% CPU, 124 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/42004336938)
[![file JFR: 0.71 GiB/s alloc, 22.7 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/42004336938)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `be4281d9415874b69c114002c0079f99775b305c`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/42004336938).

Runner: AMD EPYC 9V45 96-Core Processor; 4.0 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

Runner: AMD EPYC 9V45 96-Core Processor; 4.0 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

Runner: AMD EPYC 7763 64-Core Processor; 4.0 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

Runner: AMD EPYC 9V45 96-Core Processor; 4.0 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

Runner: INTEL(R) XEON(R) PLATINUM 8573C; 4.0 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

Runner: AMD EPYC 9V74 80-Core Processor; 4.0 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

# Node-target measurements

Each load stage uses a fresh JVM; warmup and measurement share that JVM.

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

Writes count unique records; each record needs one delivery in this workload. Write target achieved compares actual writes with requested writes. Delivery coverage compares unique deliveries with completed writes in the same window. Similar write and delivery rates do not guarantee the requested load or other limits were met.

| Mode | Reference writes/s | Median actual writes/s | Write target achieved | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 499,994 | 100.0% | 195.690 | 254 | 62.0494 | False |
| FILE | 250,000 | 165,214 | 66.1% | 11182.014 | 124 | 59.8402 | False |

**Fixed-target traffic**

| Mode | Repeat | Requested writes/s | Actual writes/s | Unique deliveries/s | Write target achieved | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 125,000 | 124,996 | 125,030 | 100.0% | 100.0% |
| FILE | 1 | 200,000 | 164,147 | 164,147 | 82.1% | 100.0% |
| FILE | 1 | 250,000 | 165,214 | 165,274 | 66.1% | 100.0% |
| FILE | 2 | 125,000 | 124,996 | 125,013 | 100.0% | 100.0% |
| FILE | 2 | 200,000 | 156,937 | 156,954 | 78.5% | 100.0% |
| FILE | 2 | 250,000 | 155,767 | 155,785 | 62.3% | 100.0% |
| FILE | 3 | 125,000 | 124,996 | 125,030 | 100.0% | 100.0% |
| FILE | 3 | 200,000 | 199,987 | 200,038 | 100.0% | 100.0% |
| FILE | 3 | 250,000 | 249,993 | 266,231 | 100.0% | 106.5% |
| IN_MEMORY | 1 | 250,000 | 249,993 | 250,001 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 400,000 | 400,000 | 400,034 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 500,000 | 499,994 | 500,045 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 250,000 | 249,993 | 250,078 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 400,000 | 400,000 | 400,077 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 500,000 | 499,994 | 501,026 | 100.0% | 100.2% |
| IN_MEMORY | 3 | 250,000 | 249,993 | 250,001 | 100.0% | 100.0% |
| IN_MEMORY | 3 | 400,000 | 400,000 | 400,111 | 100.0% | 100.0% |
| IN_MEMORY | 3 | 500,000 | 499,994 | 500,139 | 100.0% | 100.0% |

**Fixed-target latency and resource checks**

| Mode | Repeat | Load | p99 ms | CPU % | Heap slope MiB/s | Qualified window |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 563.610 | 27.5238 | -0.065 | False |
| FILE | 1 | 80% | 5330.960 | 22.3736 | 0.236 | False |
| FILE | 1 | 100% | 10083.107 | 22.7829 | 0.077 | False |
| FILE | 2 | 50% | 495.976 | 30.9158 | 0.243 | False |
| FILE | 2 | 80% | 6404.702 | 22.2000 | 0.002 | False |
| FILE | 2 | 100% | 11182.014 | 21.8074 | 0.185 | False |
| FILE | 3 | 50% | 4.407 | 49.7638 | 0.056 | True |
| FILE | 3 | 80% | 5.239 | 56.0657 | 0.116 | True |
| FILE | 3 | 100% | 22.790 | 59.8402 | 0.160 | True |
| IN_MEMORY | 1 | 50% | 0.730 | 27.4406 | 0.166 | True |
| IN_MEMORY | 1 | 80% | 0.379 | 34.8828 | 0.476 | True |
| IN_MEMORY | 1 | 100% | 0.418 | 39.5739 | 0.342 | True |
| IN_MEMORY | 2 | 50% | 0.559 | 34.4250 | 0.402 | True |
| IN_MEMORY | 2 | 80% | 0.695 | 43.4819 | 0.248 | True |
| IN_MEMORY | 2 | 100% | 195.690 | 62.0494 | -1.030 | False |
| IN_MEMORY | 3 | 50% | 0.489 | 33.7748 | 0.252 | True |
| IN_MEMORY | 3 | 80% | 0.529 | 43.0329 | -0.054 | True |
| IN_MEMORY | 3 | 100% | 0.581 | 47.7906 | -0.109 | True |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | p99 563.610 ms outside [0,10) ms |
| FILE | 1 | 80% | throughput 164,147/s <95% of offered 200,000/s; p99 5330.960 ms outside [0,100) ms |
| FILE | 1 | 100% | throughput 165,214/s <95% of offered 250,000/s; p99 10083.107 ms outside [0,100) ms |
| FILE | 2 | 50% | p99 495.976 ms outside [0,10) ms |
| FILE | 2 | 80% | throughput 156,937/s <95% of offered 200,000/s; p99 6404.702 ms outside [0,100) ms |
| FILE | 2 | 100% | throughput 155,767/s <95% of offered 250,000/s; p99 11182.014 ms outside [0,100) ms |
| IN_MEMORY | 2 | 100% | p99 195.690 ms outside [0,50) ms |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 20.070 | 0.453 | 8.561 | 563.610 |
| FILE | 1 | 80% | 8.438 | 0.802 | 8.135 | 5330.960 |
| FILE | 1 | 100% | 8.344 | 0.723 | 8.045 | 10083.107 |
| FILE | 2 | 50% | 8.962 | 0.369 | 7.037 | 495.976 |
| FILE | 2 | 80% | 8.790 | 0.677 | 8.135 | 6404.702 |
| FILE | 2 | 100% | 9.167 | 0.668 | 8.102 | 11182.014 |
| FILE | 3 | 50% | 3.641 | 0.618 | 2.329 | 4.407 |
| FILE | 3 | 80% | 3.578 | 0.738 | 2.531 | 5.239 |
| FILE | 3 | 100% | 3.604 | 1.191 | 3.088 | 22.790 |
| IN_MEMORY | 1 | 50% | 0.518 | 0.231 | 0.314 | 0.730 |
| IN_MEMORY | 1 | 80% | 0.279 | 0.206 | 0.163 | 0.379 |
| IN_MEMORY | 1 | 100% | 0.297 | 0.247 | 0.174 | 0.418 |
| IN_MEMORY | 2 | 50% | 0.415 | 0.305 | 0.156 | 0.559 |
| IN_MEMORY | 2 | 80% | 0.419 | 0.354 | 0.211 | 0.695 |
| IN_MEMORY | 2 | 100% | 1.942 | 0.807 | 0.617 | 195.690 |
| IN_MEMORY | 3 | 50% | 0.363 | 0.226 | 0.176 | 0.489 |
| IN_MEMORY | 3 | 80% | 0.364 | 0.276 | 0.184 | 0.529 |
| IN_MEMORY | 3 | 100% | 0.363 | 0.287 | 0.192 | 0.581 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 23812 | 45647.494320 | 18.006015 | 165.806993 |
| FILE | 1 | 2 | 31353 | 70284.553079 | 7.917567 | 40.453702 |
| FILE | 1 | 3 | 31617 | 69704.132423 | 7.843839 | 20.369076 |
| FILE | 2 | 1 | 23002 | 23754.271640 | 7.643135 | 201.367986 |
| FILE | 2 | 2 | 30589 | 70442.464094 | 8.187903 | 34.644667 |
| FILE | 2 | 3 | 30649 | 71023.127735 | 8.413183 | 29.021294 |
| FILE | 3 | 1 | 23987 | 15965.949267 | 2.398207 | 13.015121 |
| FILE | 3 | 2 | 39186 | 26245.526620 | 2.412543 | 7.764172 |
| FILE | 3 | 3 | 53864 | 35498.746145 | 2.226175 | 8.841949 |
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

Each CI run also doubles producer/consumer concurrency until a runtime limit under an enforced CPU/memory budget. These short exhaustion diagnostics show peak rates, sampled CPU/heap, latency and the terminal limit; consumption includes broadcast fanout. They do not qualify sustained capacity. When enabled, the optional pre-push check runs the same ramp locally and includes its results in the contributor report below.

Compare completed deliveries with the full delivery demand (unique writes × consumers). The report shows this as delivery coverage; a high fanout rate alone does not show that consumers kept up. Fixed-target reports count unique deliveries and show write-target attainment separately. See [how to read the reports](setu-benchmark/README.md#reading-the-reports).

<!-- benchmark-redline:start -->
Source revision `be4281d9415874b69c114002c0079f99775b305c`. [CI redline report and captures](https://github.com/RishabhRathi-Dev/Setu/actions/runs/42004336938)

Resource-enforced redline diagnostics (unqualified)

Source revision `be4281d9415874b69c114002c0079f99775b305c`; enforcement: systemd-cgroup-v2.

Host: AMD EPYC 9V45 96-Core Processor; filesystem/media: /dev/nvme0n1p1 ext4 (non-rotational). Allocation: 4 CPU cores, 14637 MiB process-tree memory; 8320 MiB worker heap, 1386 MiB direct memory; free-disk reserve 17.42 GiB.

Execution exit code: 0.

**Redline diagnostics: find a runtime limit (unqualified)**

**Unique writes/s** counts records appended once. This is a fanout workload: every consumer reads the same partition, so one write creates one delivery per consumer. **Deliveries needed/s = unique writes/s × consumers.** **Delivery coverage** is completed deliveries divided by (written records × consumers) within the same window.

For example, 100,000 writes/s with 8 consumers needs 800,000 deliveries/s. 400,000 completed deliveries/s covers 50% of that demand.

Coverage describes aggregate progress during the measurement window, not a record-loss rate or proof that every consumer caught up. It can slightly exceed 100% when operations cross a window boundary; n/a means no writes completed. A completed load step is not a health or capacity pass. Peak production is a short-window observation, not sustained capacity.

One fresh JVM per stage; warmup and measurement share that JVM. Heap, connections and broker state start fresh at each concurrency level. Record bytes 1024, 4096-record batches, 2s warmup and 10s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

| Mode | Highest unique writes/s | Consumers at that rate | Delivery coverage at that rate | Why the test stopped |
| --- | ---: | ---: | ---: | --- |
| FILE | 182,805 | 64 | 6.5% | JVM heap exhausted (JVM_OOM) |

**FILE: delivery progress**

The last measured stage (10, 512 consumers) covered **3.9%** of its delivery demand. The test stopped at stage 11 with 1024 producers and 1024 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 158,624 | 158,624 | 159,033 | 100.3% |
| 2 | 2 | 170,426 | 340,852 | 340,852 | 100.0% |
| 3 | 4 | 163,671 | 654,684 | 605,296 | 92.5% |
| 4 | 8 | 168,669 | 1,349,352 | 557,341 | 41.3% |
| 5 | 16 | 167,471 | 2,679,536 | 533,456 | 19.9% |
| 6 | 32 | 178,519 | 5,712,608 | 546,586 | 9.6% |
| 7 | 64 | 182,805 | 11,699,520 | 764,271 | 6.5% |
| 8 | 128 | 162,070 | 20,744,960 | 1,019,487 | 4.9% |
| 9 | 256 | 170,192 | 43,569,152 | 1,443,586 | 3.3% |
| 10 | 512 | 24,018 | 12,297,216 | 482,806 | 3.9% |

**FILE: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 10.02 | 25.1% | 3438.0 | 67.109 | Measured |
| 2 | 10.02 | 29.9% | 1726.0 | 134.218 | Measured |
| 3 | 10.04 | 34.5% | 5130.0 | 134.218 | Measured |
| 4 | 10.05 | 34.5% | 4956.0 | 268.435 | Measured |
| 5 | 10.03 | 31.7% | 2112.0 | 536.871 | Measured |
| 6 | 10.03 | 33.0% | 4374.0 | 1073.742 | Measured |
| 7 | 10.04 | 37.5% | 4208.0 | 2147.484 | Measured |
| 8 | 10.01 | 47.4% | 2552.0 | 4294.967 | Measured |
| 9 | 10.08 | 81.7% | 8318.0 | 8589.935 | Measured |
| 10 | 10.06 | 45.6% | 8318.0 | 8589.935 | Measured |

FILE: JDK 26.0.2.1; Linux; filesystem ext4; CPU percentages use 4 cores as 100%; 8320 MiB maximum heap.
Admission policy: **unbounded**. Retained-log and FILE write-queue admission are disabled; request/response credits use Long.MAX_VALUE. JVM/OS bounds, protocol constraints, the disk reserve and outer watchdog still apply. Fetch payload: 4 MiB.


Resource-enforced redline diagnostics (unqualified)

Source revision `be4281d9415874b69c114002c0079f99775b305c`; enforcement: systemd-cgroup-v2.

Host: AMD EPYC 9V74 80-Core Processor; filesystem/media: /dev/nvme0n1p1 ext4 (non-rotational). Allocation: 4 CPU cores, 14660 MiB process-tree memory; 8334 MiB worker heap, 1389 MiB direct memory; free-disk reserve 17.42 GiB.

Execution exit code: 0.

**Redline diagnostics: find a runtime limit (unqualified)**

**Unique writes/s** counts records appended once. This is a fanout workload: every consumer reads the same partition, so one write creates one delivery per consumer. **Deliveries needed/s = unique writes/s × consumers.** **Delivery coverage** is completed deliveries divided by (written records × consumers) within the same window.

For example, 100,000 writes/s with 8 consumers needs 800,000 deliveries/s. 400,000 completed deliveries/s covers 50% of that demand.

Coverage describes aggregate progress during the measurement window, not a record-loss rate or proof that every consumer caught up. It can slightly exceed 100% when operations cross a window boundary; n/a means no writes completed. A completed load step is not a health or capacity pass. Peak production is a short-window observation, not sustained capacity.

One fresh JVM per stage; warmup and measurement share that JVM. Heap, connections and broker state start fresh at each concurrency level. Record bytes 1024, 4096-record batches, 2s warmup and 10s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

| Mode | Highest unique writes/s | Consumers at that rate | Delivery coverage at that rate | Why the test stopped |
| --- | ---: | ---: | ---: | --- |
| IN_MEMORY | 1,449,257 | 1 | 76.2% | JVM heap exhausted (JVM_OOM) |

**IN_MEMORY: delivery progress**

The last measured stage (2, 2 consumers) covered **37.6%** of its delivery demand. The test stopped at stage 3 with 4 producers and 4 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 1,449,257 | 1,449,257 | 1,104,177 | 76.2% |
| 2 | 2 | 696,295 | 1,392,590 | 523,203 | 37.6% |

**IN_MEMORY: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 10.04 | 83.3% | 7666.0 | 16.777 | Measured |
| 2 | 10.44 | 63.4% | 8308.0 | 268.435 | Measured |

IN_MEMORY: JDK 26.0.2.1; Linux; filesystem ext4; CPU percentages use 4 cores as 100%; 8334 MiB maximum heap.
Admission policy: **unbounded**. Retained-log and FILE write-queue admission are disabled; request/response credits use Long.MAX_VALUE. JVM/OS bounds, protocol constraints, the disk reserve and outer watchdog still apply. Fetch payload: 4 MiB.

<!-- benchmark-redline:end -->

### Contributor PC benchmarks

Local contributor benchmarks are optional for pushes and pull requests. Use the [opt-in pre-push check](scripts/README.md#optional-contributor-pc-check) to attach local performance evidence. A lightweight CI job detects attachments matching the pushed source and enables the contributor validation stage only when evidence exists. That stage validates supplied attachments and retains them as pipeline artifacts; default-branch publication refreshes the contributor report. Without an attachment, CI skips the contributor stage and preserves the last report below. The normal CI tests and benchmarks run independently.

<!-- benchmark-contributor-pc:start -->
Latest contributor PC run (diagnostic, unqualified)

[![memory writes (contributor PC): 500.00K/s target, 324.21K/s actual, 65%, unqualified](benchmark-badges/contributor-memory-throughput.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41332297773)
[![memory p99 (contributor PC): 3514.83 ms at target](benchmark-badges/contributor-memory-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41332297773)
[![memory resources (contributor PC): 38.4% CPU, 478 MiB heap](benchmark-badges/contributor-memory-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41332297773)
[![memory JFR (contributor PC): 1.10 GiB/s alloc, 3.7 ms GC](benchmark-badges/contributor-memory-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41332297773)
[![file writes (contributor PC): 250.00K/s target, 5.80K/s actual, 2%, unqualified](benchmark-badges/contributor-file-throughput.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41332297773)
[![file p99 (contributor PC): 9722.40 ms at target](benchmark-badges/contributor-file-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41332297773)
[![file resources (contributor PC): 9.4% CPU, 388 MiB heap](benchmark-badges/contributor-file-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41332297773)
[![file JFR (contributor PC): 0.03 GiB/s alloc, 0.2 ms GC](benchmark-badges/contributor-file-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41332297773)

[Report, logs and downloadable results](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41332297773)

Contributor: Rishabh Rathi; finished 2026-11-01T16:40:08.471012+00:00; revision `0a56bef252ea1ecf9fa32a141ae60ada24211f22`.

Windows-11-10.0.26200-SP0; AMD Ryzen 5 4600H with Radeon Graphics; 8 JVM active processors; 512 MiB heap; 128 MiB direct-memory limit; no OS resource quota.

JDK: openjdk version "26.0.1" 2026-04-21 OpenJDK Runtime Environment (build 26.0.1+8-34) OpenJDK 64-Bit Server VM (build 26.0.1+8-34, mixed mode, sharing). Media: Filesystem per worker; physical media not detected.

Two fresh JVMs per mode; 1 KiB records; 256-record batches; 5s minimum adaptive warmup and 10s measurement at each load. Broker and clients share the PC. These results are contributor supplied and do not qualify sustained capacity.

**Contributor PC summary (diagnostic, unqualified)**

CI-style summary: median actual writes/s across the two full-target windows; worst p99 at that target. Heap, average CPU, allocation rate and GC pause values are maxima across all fixed-target windows. Window checks may pass while these contributor measurements remain unqualified.

| Mode | Reference writes/s | Median actual writes/s | Write target achieved | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Window checks | Status |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| IN_MEMORY | 500,000 | 324,211 | 64.8% | 3514.827 | 478.0 | 38.4407 | Missed | Unqualified |
| FILE | 250,000 | 5,798 | 2.3% | 9722.397 | 388.0 | 9.4411 | Missed | Unqualified |

**Fixed target: requested writes versus completed work**

These windows count unique records, with one delivery per record. Requested writes/s is the offered load; actual writes/s is the completed rate. Write target achieved compares actual with requested writes. Delivery coverage compares unique deliveries with completed writes. Consumers keeping up does not mean the write target was met.

| Mode | Repeat | Requested writes/s | Actual writes/s | Unique deliveries/s | Write target achieved | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| IN_MEMORY | 1 | 250,000 | 249,984 | 253,107 | 100.0% | 101.2% |
| IN_MEMORY | 1 | 400,000 | 318,054 | 318,080 | 79.5% | 100.0% |
| IN_MEMORY | 1 | 500,000 | 322,560 | 322,560 | 64.5% | 100.0% |
| FILE | 1 | 125,000 | 5,120 | 5,222 | 4.1% | 102.0% |
| FILE | 1 | 200,000 | 5,658 | 6,144 | 2.8% | 108.6% |
| FILE | 1 | 250,000 | 6,042 | 6,118 | 2.4% | 101.3% |
| IN_MEMORY | 2 | 250,000 | 249,677 | 252,621 | 99.9% | 101.2% |
| IN_MEMORY | 2 | 400,000 | 333,773 | 333,722 | 83.4% | 100.0% |
| IN_MEMORY | 2 | 500,000 | 325,862 | 325,888 | 65.2% | 100.0% |
| FILE | 2 | 125,000 | 3,405 | 2,739 | 2.7% | 80.5% |
| FILE | 2 | 200,000 | 5,709 | 6,118 | 2.9% | 107.2% |
| FILE | 2 | 250,000 | 5,555 | 5,862 | 2.2% | 105.5% |

**Fixed target: latency and resource checks**

Window checks include throughput, consumption, latency, CPU and heap limits. A passed window remains a local diagnostic, not qualified sustained capacity.

| Mode | Repeat | Load | p99 ms | CPU budget used | Force p99 ms | Window checks |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 1 | 50% | 200.671 | 31.5% | 0.000 | Missed |
| IN_MEMORY | 1 | 80% | 2028.995 | 37.1% | 0.000 | Missed |
| IN_MEMORY | 1 | 100% | 3514.827 | 38.4% | 0.000 | Missed |
| FILE | 1 | 50% | 9529.459 | 7.0% | 78.840 | Missed |
| FILE | 1 | 80% | 9680.454 | 7.4% | 37.618 | Missed |
| FILE | 1 | 100% | 9722.397 | 6.8% | 56.001 | Missed |
| IN_MEMORY | 2 | 50% | 14.352 | 31.2% | 0.000 | Missed |
| IN_MEMORY | 2 | 80% | 1646.264 | 37.6% | 0.000 | Missed |
| IN_MEMORY | 2 | 100% | 3456.106 | 38.2% | 0.000 | Missed |
| FILE | 2 | 50% | 9789.506 | 4.4% | 98.238 | Missed |
| FILE | 2 | 80% | 9697.231 | 9.4% | 60.555 | Missed |
| FILE | 2 | 100% | 9705.619 | 7.9% | 56.001 | Missed |

**Redline diagnostics: find a runtime limit (unqualified)**

**Unique writes/s** counts records appended once. This is a fanout workload: every consumer reads the same partition, so one write creates one delivery per consumer. **Deliveries needed/s = unique writes/s × consumers.** **Delivery coverage** is completed deliveries divided by (written records × consumers) within the same window.

For example, 100,000 writes/s with 8 consumers needs 800,000 deliveries/s. 400,000 completed deliveries/s covers 50% of that demand.

Coverage describes aggregate progress during the measurement window, not a record-loss rate or proof that every consumer caught up. It can slightly exceed 100% when operations cross a window boundary; n/a means no writes completed. A completed load step is not a health or capacity pass. Peak production is a short-window observation, not sustained capacity.

One fresh JVM per stage; warmup and measurement share that JVM. Heap, connections and broker state start fresh at each concurrency level. Record bytes 1024, 4096-record batches, 2s warmup and 10s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

| Mode | Highest unique writes/s | Consumers at that rate | Delivery coverage at that rate | Why the test stopped |
| --- | ---: | ---: | ---: | --- |
| IN_MEMORY | 298,264 | 1 | 95.8% | JVM heap exhausted (JVM_OOM) |
| FILE | 80,882 | 64 | 3.3% | JVM heap exhausted (JVM_OOM) |

**IN_MEMORY: delivery progress**

The last measured stage (1, 1 consumers) covered **95.8%** of its delivery demand. The test stopped at stage 2 with 2 producers and 2 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 298,264 | 298,264 | 285,702 | 95.8% |

**IN_MEMORY: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 10.11 | 29.2% | 1268.0 | 268.435 | Measured |

IN_MEMORY: JDK 26.0.1; Windows_11; filesystem NTFS; CPU percentages use 12 cores as 100%; 1432 MiB maximum heap.
Free-disk reserve: 156.38 GiB (sampled).
Admission policy: **unbounded**. Retained-log and FILE write-queue admission are disabled; request/response credits use Long.MAX_VALUE. JVM/OS bounds, protocol constraints, the disk reserve and outer watchdog still apply. Fetch payload: 4 MiB.

**FILE: delivery progress**

The last measured stage (7, 64 consumers) covered **3.3%** of its delivery demand. The test stopped at stage 8 with 128 producers and 128 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 34,518 | 34,518 | 34,919 | 101.2% |
| 2 | 2 | 36,067 | 72,134 | 73,350 | 101.7% |
| 3 | 4 | 45,025 | 180,100 | 94,874 | 52.7% |
| 4 | 8 | 57,912 | 463,296 | 115,824 | 25.0% |
| 5 | 16 | 76,231 | 1,219,696 | 134,809 | 11.1% |
| 6 | 32 | 66,974 | 2,143,168 | 170,443 | 8.0% |
| 7 | 64 | 80,882 | 5,176,448 | 168,301 | 3.3% |

**FILE: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 10.20 | 7.5% | 612.0 | 268.435 | Measured |
| 2 | 10.11 | 10.1% | 1310.0 | 536.871 | Measured |
| 3 | 10.19 | 10.8% | 748.0 | 1073.742 | Measured |
| 4 | 10.18 | 14.7% | 1432.0 | 1073.742 | Measured |
| 5 | 10.21 | 18.2% | 874.0 | 2147.484 | Measured |
| 6 | 10.21 | 23.7% | 928.0 | 2147.484 | Measured |
| 7 | 10.03 | 39.6% | 1414.0 | 8589.935 | Measured |

FILE: JDK 26.0.1; Windows_11; filesystem NTFS; CPU percentages use 12 cores as 100%; 1432 MiB maximum heap.
Free-disk reserve: 156.38 GiB (sampled).
Admission policy: **unbounded**. Retained-log and FILE write-queue admission are disabled; request/response credits use Long.MAX_VALUE. JVM/OS bounds, protocol constraints, the disk reserve and outer watchdog still apply. Fetch payload: 4 MiB.
<!-- benchmark-contributor-pc:end -->

## Help and contributions

For setup help or a reproducible bug, start with [support and troubleshooting](SUPPORT.md). Use the [issue forms](https://github.com/RishabhRathi-Dev/Setu/issues/new/choose) for bugs and feature requests. Report vulnerabilities privately following the [security policy](SECURITY.md).

Contributions should include focused changes, relevant verification, and updated documentation. The [contributing guide](CONTRIBUTING.md) covers setup, checks, and pull requests; [AGENTS.md](AGENTS.md) records the repository map and contracts for coding agents.
