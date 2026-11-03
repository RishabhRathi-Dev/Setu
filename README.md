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
[![in-memory: 500.00K/s target, 446.06K/s CI, 89% unqualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41703662238)
[![file: 250.00K/s target, 164.23K/s CI, 66% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41703662238)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest CI target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 4037.02 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41703662238)
[![memory resources: 78% CPU, 254 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41703662238)
[![memory JFR: 1.48 GiB/s alloc, 74.7 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41703662238)
[![file p99: 11249.12 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41703662238)
[![file resources: 60% CPU, 126 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41703662238)
[![file JFR: 0.71 GiB/s alloc, 22.2 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41703662238)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `828f3a81d5626a58e8c113e1217bcc52c04e19bf`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41703662238).

Runner: AMD EPYC 9V74 80-Core Processor; 4.0 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

Runner: AMD EPYC 9V45 96-Core Processor; 4.0 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

Runner: AMD EPYC 7763 64-Core Processor; 4.0 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

Runner: AMD EPYC 7763 64-Core Processor; 4.0 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

Runner: AMD EPYC 7763 64-Core Processor; 4.0 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

Runner: Intel(R) Xeon(R) 6973P-C; 4.0 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/nvme1n1p1 ext4 (non-rotational).

# Node-target measurements

Each load stage uses a fresh JVM; warmup and measurement share that JVM.

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

Writes count unique records; each record needs one delivery in this workload. Write target achieved compares actual writes with requested writes. Delivery coverage compares unique deliveries with completed writes in the same window. Similar write and delivery rates do not guarantee the requested load or other limits were met.

| Mode | Reference writes/s | Median actual writes/s | Write target achieved | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 446,063 | 89.2% | 4037.018 | 254 | 77.6725 | False |
| FILE | 250,000 | 164,233 | 65.7% | 11249.123 | 126 | 59.5136 | False |

**Fixed-target traffic**

| Mode | Repeat | Requested writes/s | Actual writes/s | Unique deliveries/s | Write target achieved | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 125,000 | 120,951 | 120,875 | 96.8% | 99.9% |
| FILE | 1 | 200,000 | 138,505 | 138,249 | 69.3% | 99.8% |
| FILE | 1 | 250,000 | 155,520 | 155,520 | 62.2% | 100.0% |
| FILE | 2 | 125,000 | 124,996 | 125,022 | 100.0% | 100.0% |
| FILE | 2 | 200,000 | 162,714 | 162,705 | 81.4% | 100.0% |
| FILE | 2 | 250,000 | 164,233 | 164,275 | 65.7% | 100.0% |
| FILE | 3 | 125,000 | 124,996 | 125,022 | 100.0% | 100.0% |
| FILE | 3 | 200,000 | 199,987 | 200,030 | 100.0% | 100.0% |
| FILE | 3 | 250,000 | 249,993 | 264,098 | 100.0% | 105.6% |
| IN_MEMORY | 1 | 250,000 | 249,993 | 250,035 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 400,000 | 400,000 | 401,007 | 100.0% | 100.3% |
| IN_MEMORY | 1 | 500,000 | 432,094 | 432,111 | 86.4% | 100.0% |
| IN_MEMORY | 2 | 250,000 | 249,993 | 250,044 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 400,000 | 400,000 | 401,033 | 100.0% | 100.3% |
| IN_MEMORY | 2 | 500,000 | 446,063 | 446,054 | 89.2% | 100.0% |
| IN_MEMORY | 3 | 250,000 | 249,993 | 250,001 | 100.0% | 100.0% |
| IN_MEMORY | 3 | 400,000 | 400,000 | 400,119 | 100.0% | 100.0% |
| IN_MEMORY | 3 | 500,000 | 499,994 | 500,156 | 100.0% | 100.0% |

**Fixed-target latency and resource checks**

| Mode | Repeat | Load | p99 ms | CPU % | Heap slope MiB/s | Qualified window |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 1047.527 | 20.8159 | 0.099 | False |
| FILE | 1 | 80% | 9185.526 | 22.5740 | 0.152 | False |
| FILE | 1 | 100% | 11249.123 | 24.1327 | 0.004 | False |
| FILE | 2 | 50% | 753.402 | 23.9826 | 0.336 | False |
| FILE | 2 | 80% | 5549.064 | 19.7578 | 0.083 | False |
| FILE | 2 | 100% | 10200.547 | 20.0744 | 0.083 | False |
| FILE | 3 | 50% | 4.841 | 49.2381 | 0.136 | True |
| FILE | 3 | 80% | 7.098 | 55.3731 | 0.161 | True |
| FILE | 3 | 100% | 25.674 | 59.5136 | 0.176 | True |
| IN_MEMORY | 1 | 50% | 0.614 | 42.9242 | 0.249 | True |
| IN_MEMORY | 1 | 80% | 49.775 | 55.2652 | -0.302 | True |
| IN_MEMORY | 1 | 100% | 4037.018 | 77.6725 | -0.022 | False |
| IN_MEMORY | 2 | 50% | 0.695 | 43.1821 | 0.147 | True |
| IN_MEMORY | 2 | 80% | 39.322 | 55.3832 | -0.132 | True |
| IN_MEMORY | 2 | 100% | 3206.545 | 77.2466 | 0.107 | False |
| IN_MEMORY | 3 | 50% | 0.476 | 31.5658 | 0.118 | True |
| IN_MEMORY | 3 | 80% | 0.510 | 39.5739 | 0.261 | True |
| IN_MEMORY | 3 | 100% | 0.577 | 45.0412 | 0.241 | True |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | p99 1047.527 ms outside [0,10) ms |
| FILE | 1 | 80% | throughput 138,505/s <95% of offered 200,000/s; p99 9185.526 ms outside [0,100) ms |
| FILE | 1 | 100% | throughput 155,520/s <95% of offered 250,000/s; p99 11249.123 ms outside [0,100) ms |
| FILE | 2 | 50% | p99 753.402 ms outside [0,10) ms |
| FILE | 2 | 80% | throughput 162,714/s <95% of offered 200,000/s; p99 5549.064 ms outside [0,100) ms |
| FILE | 2 | 100% | throughput 164,233/s <95% of offered 250,000/s; p99 10200.547 ms outside [0,100) ms |
| IN_MEMORY | 1 | 100% | throughput 432,094/s <95% of offered 500,000/s; p99 4037.018 ms outside [0,50) ms |
| IN_MEMORY | 2 | 100% | throughput 446,063/s <95% of offered 500,000/s; p99 3206.545 ms outside [0,50) ms |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 29.327 | 0.685 | 24.838 | 1047.527 |
| FILE | 1 | 80% | 27.542 | 0.754 | 19.857 | 9185.526 |
| FILE | 1 | 100% | 8.684 | 0.829 | 8.270 | 11249.123 |
| FILE | 2 | 50% | 22.069 | 0.428 | 10.101 | 753.402 |
| FILE | 2 | 80% | 8.528 | 0.647 | 8.221 | 5549.064 |
| FILE | 2 | 100% | 8.462 | 0.703 | 8.237 | 10200.547 |
| FILE | 3 | 50% | 3.684 | 0.613 | 2.433 | 4.841 |
| FILE | 3 | 80% | 3.867 | 0.774 | 2.857 | 7.098 |
| FILE | 3 | 100% | 3.668 | 1.223 | 3.256 | 25.674 |
| IN_MEMORY | 1 | 50% | 0.447 | 0.397 | 0.191 | 0.614 |
| IN_MEMORY | 1 | 80% | 1.329 | 0.534 | 0.376 | 49.775 |
| IN_MEMORY | 1 | 100% | 2.210 | 1.103 | 0.859 | 4037.018 |
| IN_MEMORY | 2 | 50% | 0.466 | 0.339 | 0.213 | 0.695 |
| IN_MEMORY | 2 | 80% | 0.861 | 0.481 | 0.345 | 39.322 |
| IN_MEMORY | 2 | 100% | 2.195 | 1.108 | 0.839 | 3206.545 |
| IN_MEMORY | 3 | 50% | 0.374 | 0.368 | 0.180 | 0.476 |
| IN_MEMORY | 3 | 80% | 0.346 | 0.323 | 0.169 | 0.510 |
| IN_MEMORY | 3 | 100% | 0.374 | 0.358 | 0.198 | 0.577 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 23677 | 70072.529308 | 27.049983 | 189.518818 |
| FILE | 1 | 2 | 26882 | 70903.501729 | 20.103167 | 127.614485 |
| FILE | 1 | 3 | 29820 | 69244.679527 | 7.876607 | 208.078532 |
| FILE | 2 | 1 | 23382 | 52153.901774 | 19.824639 | 145.542497 |
| FILE | 2 | 2 | 30440 | 71715.327874 | 8.056831 | 41.703075 |
| FILE | 2 | 3 | 30556 | 71596.965089 | 8.024063 | 29.962248 |
| FILE | 3 | 1 | 23943 | 15934.921833 | 2.482175 | 8.762371 |
| FILE | 3 | 2 | 39237 | 27349.944804 | 2.576383 | 11.534479 |
| FILE | 3 | 3 | 53575 | 35562.218370 | 2.289663 | 9.104136 |
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
Source revision `828f3a81d5626a58e8c113e1217bcc52c04e19bf`. [CI redline report and captures](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41703662238)

Resource-enforced redline diagnostics (unqualified)

Source revision `828f3a81d5626a58e8c113e1217bcc52c04e19bf`; enforcement: systemd-cgroup-v2.

Host: AMD EPYC 9V74 80-Core Processor; filesystem/media: /dev/nvme0n1p1 ext4 (non-rotational). Allocation: 4 CPU cores, 14665 MiB process-tree memory; 8338 MiB worker heap, 1389 MiB direct memory; free-disk reserve 17.42 GiB.

Execution exit code: 0.

**Redline diagnostics: find a runtime limit (unqualified)**

**Unique writes/s** counts records appended once. This is a fanout workload: every consumer reads the same partition, so one write creates one delivery per consumer. **Deliveries needed/s = unique writes/s × consumers.** **Delivery coverage** is completed deliveries divided by (written records × consumers) within the same window.

For example, 100,000 writes/s with 8 consumers needs 800,000 deliveries/s. 400,000 completed deliveries/s covers 50% of that demand.

Coverage describes aggregate progress during the measurement window, not a record-loss rate or proof that every consumer caught up. It can slightly exceed 100% when operations cross a window boundary; n/a means no writes completed. A completed load step is not a health or capacity pass. Peak production is a short-window observation, not sustained capacity.

One fresh JVM per stage; warmup and measurement share that JVM. Heap, connections and broker state start fresh at each concurrency level. Record bytes 1024, 4096-record batches, 2s warmup and 10s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

| Mode | Highest unique writes/s | Consumers at that rate | Delivery coverage at that rate | Why the test stopped |
| --- | ---: | ---: | ---: | --- |
| FILE | 172,072 | 16 | 22.5% | JVM heap exhausted (JVM_OOM) |

**FILE: delivery progress**

The last measured stage (11, 1024 consumers) covered **n/a** of its delivery demand. The test stopped at stage 11 with 1024 producers and 1024 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 127,426 | 127,426 | 127,017 | 99.7% |
| 2 | 2 | 156,975 | 313,950 | 313,133 | 99.7% |
| 3 | 4 | 149,490 | 597,960 | 573,045 | 95.8% |
| 4 | 8 | 150,544 | 1,204,352 | 506,707 | 42.1% |
| 5 | 16 | 172,072 | 2,753,152 | 620,032 | 22.5% |
| 6 | 32 | 166,355 | 5,323,360 | 472,562 | 8.9% |
| 7 | 64 | 159,631 | 10,216,384 | 698,384 | 6.8% |
| 8 | 128 | 157,186 | 20,119,808 | 893,178 | 4.4% |
| 9 | 256 | 124,900 | 31,974,400 | 1,141,079 | 3.6% |
| 10 | 512 | 21,361 | 10,936,832 | 479,515 | 4.4% |
| 11 | 1024 | 0 | 0 | 0 | n/a |

**FILE: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 10.03 | 25.7% | 2526.0 | 268.435 | Measured |
| 2 | 10.02 | 32.7% | 7574.0 | 134.218 | Measured |
| 3 | 10.03 | 36.4% | 4790.0 | 268.435 | Measured |
| 4 | 10.04 | 35.6% | 1582.0 | 536.871 | Measured |
| 5 | 10.02 | 38.9% | 3866.0 | 536.871 | Measured |
| 6 | 10.05 | 34.5% | 3904.0 | 1073.742 | Measured |
| 7 | 10.06 | 39.7% | 2026.0 | 2147.484 | Measured |
| 8 | 10.01 | 47.2% | 3406.0 | 4294.967 | Measured |
| 9 | 10.13 | 73.1% | 6206.0 | 8589.935 | Measured |
| 10 | 11.12 | 51.4% | 8192.0 | 17179.869 | Measured |
| 11 | 1.76 | 93.6% | 7416.0 | 0.000 | Partial CPU hold |

FILE: JDK 26.0.2.1; Linux; filesystem ext4; CPU percentages use 4 cores as 100%; 8338 MiB maximum heap.
Admission policy: **unbounded**. Retained-log and FILE write-queue admission are disabled; request/response credits use Long.MAX_VALUE. JVM/OS bounds, protocol constraints, the disk reserve and outer watchdog still apply. Fetch payload: 4 MiB.


Resource-enforced redline diagnostics (unqualified)

Source revision `828f3a81d5626a58e8c113e1217bcc52c04e19bf`; enforcement: systemd-cgroup-v2.

Host: AMD EPYC 9V74 80-Core Processor; filesystem/media: /dev/sda1 ext4 (rotational). Allocation: 4 CPU cores, 14671 MiB process-tree memory; 8341 MiB worker heap, 1390 MiB direct memory; free-disk reserve 17.42 GiB.

Execution exit code: 0.

**Redline diagnostics: find a runtime limit (unqualified)**

**Unique writes/s** counts records appended once. This is a fanout workload: every consumer reads the same partition, so one write creates one delivery per consumer. **Deliveries needed/s = unique writes/s × consumers.** **Delivery coverage** is completed deliveries divided by (written records × consumers) within the same window.

For example, 100,000 writes/s with 8 consumers needs 800,000 deliveries/s. 400,000 completed deliveries/s covers 50% of that demand.

Coverage describes aggregate progress during the measurement window, not a record-loss rate or proof that every consumer caught up. It can slightly exceed 100% when operations cross a window boundary; n/a means no writes completed. A completed load step is not a health or capacity pass. Peak production is a short-window observation, not sustained capacity.

One fresh JVM per stage; warmup and measurement share that JVM. Heap, connections and broker state start fresh at each concurrency level. Record bytes 1024, 4096-record batches, 2s warmup and 10s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

| Mode | Highest unique writes/s | Consumers at that rate | Delivery coverage at that rate | Why the test stopped |
| --- | ---: | ---: | ---: | --- |
| IN_MEMORY | 1,242,203 | 1 | 77.4% | JVM heap exhausted (JVM_OOM) |

**IN_MEMORY: delivery progress**

The last measured stage (3, 4 consumers) covered **15.5%** of its delivery demand. The test stopped at stage 4 with 8 producers and 8 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 1,242,203 | 1,242,203 | 961,942 | 77.4% |
| 2 | 2 | 697,156 | 1,394,312 | 524,807 | 37.6% |
| 3 | 4 | 514,499 | 2,057,996 | 318,326 | 15.5% |

**IN_MEMORY: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 10.04 | 81.3% | 6872.0 | 16.777 | Measured |
| 2 | 10.03 | 70.4% | 8342.0 | 268.435 | Measured |
| 3 | 10.13 | 64.7% | 8330.0 | 536.871 | Measured |

IN_MEMORY: JDK 26.0.2.1; Linux; filesystem ext4; CPU percentages use 4 cores as 100%; 8342 MiB maximum heap.
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
