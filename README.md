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
[![in-memory: 500.00K/s target, 499.99K/s CI, 100% qualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40927196794)
[![file: 250.00K/s target, 172.85K/s CI, 69% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40927196794)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest CI target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 0.31 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40927196794)
[![memory resources: 39% CPU, 256 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40927196794)
[![memory JFR: 1.67 GiB/s alloc, 8.6 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40927196794)
[![file p99: 10812.92 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40927196794)
[![file resources: 38% CPU, 126 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40927196794)
[![file JFR: 0.66 GiB/s alloc, 10.7 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40927196794)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `4e65282832d61710fa5e7b898842ab3f16d7386d`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40927196794).

Runner: Intel(R) Xeon(R) 6973P-C; 4.0 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

Runner: AMD EPYC 9V45 96-Core Processor; 4.0 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

| Mode | Target/s | Median measured/s | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 499,994 | 0.308 | 256 | 39.0659 | True |
| FILE | 250,000 | 172,851 | 10812.916 | 126 | 37.6323 | False |

| Mode | Repeat | Load | Measured/s | Consumed/s | p99 ms | CPU % | Heap slope MiB/s | Healthy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 124,996 | 125,030 | 585.630 | 28.5655 | 0.120 | False |
| FILE | 1 | 80% | 163,209 | 156,339 | 6064.964 | 26.8324 | 0.007 | False |
| FILE | 1 | 100% | 173,184 | 149,419 | 10745.807 | 26.7661 | -0.150 | False |
| FILE | 2 | 50% | 124,996 | 125,022 | 2.000 | 37.3068 | 0.095 | True |
| FILE | 2 | 80% | 171,153 | 151,987 | 6182.404 | 27.0743 | -0.001 | False |
| FILE | 2 | 100% | 159,104 | 158,925 | 10812.916 | 26.4572 | -0.113 | False |
| FILE | 3 | 50% | 124,996 | 125,013 | 2.159 | 37.6323 | 0.158 | True |
| FILE | 3 | 80% | 172,279 | 151,509 | 6354.371 | 27.8323 | 0.126 | False |
| FILE | 3 | 100% | 172,851 | 148,608 | 10745.807 | 27.2659 | 0.053 | False |
| IN_MEMORY | 1 | 50% | 249,993 | 250,018 | 0.347 | 26.3915 | 0.045 | True |
| IN_MEMORY | 1 | 80% | 400,000 | 400,017 | 0.304 | 33.0664 | 0.136 | True |
| IN_MEMORY | 1 | 100% | 499,994 | 500,002 | 0.275 | 37.3320 | 0.474 | True |
| IN_MEMORY | 2 | 50% | 249,993 | 249,950 | 0.285 | 26.0736 | 0.220 | True |
| IN_MEMORY | 2 | 80% | 400,000 | 400,009 | 0.264 | 32.9576 | 0.109 | True |
| IN_MEMORY | 2 | 100% | 499,994 | 500,002 | 0.280 | 37.2493 | 0.469 | True |
| IN_MEMORY | 3 | 50% | 249,993 | 250,001 | 0.293 | 26.4414 | 0.095 | True |
| IN_MEMORY | 3 | 80% | 400,000 | 400,017 | 0.272 | 34.1246 | 0.207 | True |
| IN_MEMORY | 3 | 100% | 499,994 | 500,011 | 0.308 | 39.0659 | 0.306 | True |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | p99 585.630 ms outside [0,10) ms |
| FILE | 1 | 80% | throughput 163,209/s <95% of offered 200,000/s; p99 6064.964 ms outside [0,100) ms |
| FILE | 1 | 100% | consumption 86.3% <95%; throughput 173,184/s <95% of offered 250,000/s; p99 10745.807 ms outside [0,100) ms |
| FILE | 2 | 80% | consumption 88.8% <95%; throughput 171,153/s <95% of offered 200,000/s; p99 6182.404 ms outside [0,100) ms |
| FILE | 2 | 100% | throughput 159,104/s <95% of offered 250,000/s; p99 10812.916 ms outside [0,100) ms |
| FILE | 3 | 80% | consumption 87.9% <95%; throughput 172,279/s <95% of offered 200,000/s; p99 6354.371 ms outside [0,100) ms |
| FILE | 3 | 100% | consumption 86.0% <95%; throughput 172,851/s <95% of offered 250,000/s; p99 10745.807 ms outside [0,100) ms |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 21.135 | 0.686 | 16.564 | 585.630 |
| FILE | 1 | 80% | 7.881 | 0.950 | 7.533 | 6064.964 |
| FILE | 1 | 100% | 7.848 | 1.029 | 7.684 | 10745.807 |
| FILE | 2 | 50% | 1.874 | 0.442 | 1.303 | 2.000 |
| FILE | 2 | 80% | 7.893 | 1.048 | 7.643 | 6182.404 |
| FILE | 2 | 100% | 7.975 | 0.925 | 7.520 | 10812.916 |
| FILE | 3 | 50% | 1.999 | 0.439 | 1.305 | 2.159 |
| FILE | 3 | 80% | 7.844 | 1.041 | 7.578 | 6354.371 |
| FILE | 3 | 100% | 7.873 | 1.028 | 7.655 | 10745.807 |
| IN_MEMORY | 1 | 50% | 0.250 | 0.148 | 0.088 | 0.347 |
| IN_MEMORY | 1 | 80% | 0.213 | 0.152 | 0.090 | 0.304 |
| IN_MEMORY | 1 | 100% | 0.196 | 0.151 | 0.088 | 0.275 |
| IN_MEMORY | 2 | 50% | 0.211 | 0.143 | 0.082 | 0.285 |
| IN_MEMORY | 2 | 80% | 0.194 | 0.149 | 0.084 | 0.264 |
| IN_MEMORY | 2 | 100% | 0.195 | 0.150 | 0.085 | 0.280 |
| IN_MEMORY | 3 | 50% | 0.209 | 0.146 | 0.085 | 0.293 |
| IN_MEMORY | 3 | 80% | 0.199 | 0.156 | 0.088 | 0.272 |
| IN_MEMORY | 3 | 100% | 0.215 | 0.164 | 0.100 | 0.308 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 26132 | 53504.891579 | 19.136511 | 158.313616 |
| FILE | 1 | 2 | 33124 | 66560.101150 | 7.172095 | 117.884087 |
| FILE | 1 | 3 | 34394 | 65447.811652 | 7.143423 | 45.709943 |
| FILE | 2 | 1 | 23453 | 10481.170767 | 0.912895 | 9.538828 |
| FILE | 2 | 2 | 34376 | 65640.698629 | 7.122943 | 41.859376 |
| FILE | 2 | 3 | 32420 | 67531.324087 | 7.221247 | 45.807017 |
| FILE | 3 | 1 | 23922 | 11538.370992 | 1.056767 | 10.129466 |
| FILE | 3 | 2 | 34572 | 65121.622616 | 7.118847 | 38.030906 |
| FILE | 3 | 3 | 34506 | 65443.545899 | 7.167999 | 37.406704 |
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

<!-- benchmark-redline:start -->
Source revision `4e65282832d61710fa5e7b898842ab3f16d7386d`. [CI redline report and captures](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40927196794)

Resource-enforced redline diagnostics (unqualified)

Source revision `4e65282832d61710fa5e7b898842ab3f16d7386d`; enforcement: systemd-cgroup-v2.

Host: AMD EPYC 9V45 96-Core Processor; filesystem/media: /dev/nvme0n1p1 ext4 (non-rotational). Allocation: 2 CPU cores, 6554 MiB process-tree memory; 512 MiB worker heap, 128 MiB direct memory; free-disk reserve 17.03 GiB.

Execution exit code: 0.

Redline diagnostics (unqualified). One fresh JVM per mode; record bytes 1024, 256-record batches, 2s warmup and 2s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

Consumption includes broadcast fanout. Peak production is a short-window observation, not sustained capacity. The last measured window can precede the terminal failure; a CPU_HOLD row is a partial window.

| Mode | Peak produced/s | Last measured pairs | Stop stage / attempted pairs | Stop reason | Peak CPU % | Max reported heap MiB | Worst p99 ms |
| --- | ---: | ---: | --- | --- | ---: | ---: | ---: |
| FILE | 161,660 | 512 | 10 / 512 | BROKER_OR_CLIENT_LIMIT | 100.0% | 464.0 | 2147.484 |

| Mode | Stage | Pairs | Duration s | Produced/s | Fanout consumed/s | Average CPU % | Reported heap MiB | p99 ms | Window |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 1 | 2.01 | 148,361 | 148,105 | 68.4% | 252.0 | 8.389 | ramp |
| FILE | 2 | 2 | 2.01 | 137,348 | 239,720 | 64.4% | 222.0 | 16.777 | ramp |
| FILE | 3 | 4 | 2.00 | 41,138 | 87,642 | 19.7% | 186.0 | 268.435 | ramp |
| FILE | 4 | 8 | 2.00 | 146,745 | 321,333 | 53.2% | 122.0 | 67.109 | ramp |
| FILE | 5 | 16 | 2.00 | 152,721 | 402,873 | 43.4% | 194.0 | 67.109 | ramp |
| FILE | 6 | 32 | 2.01 | 157,681 | 492,561 | 47.6% | 164.0 | 134.218 | ramp |
| FILE | 7 | 64 | 2.00 | 161,660 | 505,284 | 49.2% | 168.0 | 134.218 | ramp |
| FILE | 8 | 128 | 2.04 | 145,757 | 297,801 | 97.1% | 412.0 | 536.871 | ramp |
| FILE | 9 | 256 | 2.05 | 72,923 | 164,419 | 94.4% | 462.0 | 2147.484 | ramp |
| FILE | 10 | 512 | 1.90 | 17,106 | 67,749 | 96.2% | 464.0 | 2147.484 | BROKER_OR_CLIENT_LIMIT |

FILE: JDK 26.0.2.1; Linux; filesystem ext4; 2 CPU budget; 512 MiB maximum heap.


Resource-enforced redline diagnostics (unqualified)

Source revision `4e65282832d61710fa5e7b898842ab3f16d7386d`; enforcement: systemd-cgroup-v2.

Host: AMD EPYC 9V74 80-Core Processor; filesystem/media: /dev/nvme0n1p1 ext4 (non-rotational). Allocation: 2 CPU cores, 6554 MiB process-tree memory; 512 MiB worker heap, 128 MiB direct memory; free-disk reserve 17.03 GiB.

Execution exit code: 0.

Redline diagnostics (unqualified). One fresh JVM per mode; record bytes 1024, 256-record batches, 2s warmup and 2s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

Consumption includes broadcast fanout. Peak production is a short-window observation, not sustained capacity. The last measured window can precede the terminal failure; a CPU_HOLD row is a partial window.

| Mode | Peak produced/s | Last measured pairs | Stop stage / attempted pairs | Stop reason | Peak CPU % | Max reported heap MiB | Worst p99 ms |
| --- | ---: | ---: | --- | --- | ---: | ---: | ---: |
| IN_MEMORY | 417,110 | 1024 | 12 / 2048 | JVM_OOM | 100.0% | 510.0 | 1073.742 |

| Mode | Stage | Pairs | Duration s | Produced/s | Fanout consumed/s | Average CPU % | Reported heap MiB | p99 ms | Window |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 1 | 1 | 2.04 | 417,110 | 402,065 | 96.6% | 346.0 | 2.097 | ramp |
| IN_MEMORY | 2 | 2 | 2.01 | 364,511 | 698,263 | 95.2% | 176.0 | 4.194 | ramp |
| IN_MEMORY | 3 | 4 | 2.06 | 223,951 | 838,233 | 95.2% | 260.0 | 33.554 | ramp |
| IN_MEMORY | 4 | 8 | 2.07 | 151,627 | 1,092,482 | 96.3% | 262.0 | 33.554 | ramp |
| IN_MEMORY | 5 | 16 | 2.02 | 88,879 | 1,217,420 | 96.9% | 398.0 | 67.109 | ramp |
| IN_MEMORY | 6 | 32 | 2.01 | 50,733 | 1,433,399 | 96.4% | 246.0 | 67.109 | ramp |
| IN_MEMORY | 7 | 64 | 2.01 | 26,151 | 1,337,936 | 95.7% | 204.0 | 67.109 | ramp |
| IN_MEMORY | 8 | 128 | 2.03 | 22,032 | 1,256,431 | 93.8% | 304.0 | 67.109 | ramp |
| IN_MEMORY | 9 | 256 | 2.07 | 23,914 | 1,011,832 | 95.2% | 308.0 | 134.218 | ramp |
| IN_MEMORY | 10 | 512 | 2.03 | 23,156 | 866,968 | 94.1% | 510.0 | 536.871 | ramp |
| IN_MEMORY | 11 | 1024 | 2.10 | 26,400 | 371,066 | 87.6% | 456.0 | 1073.742 | ramp |

IN_MEMORY: JDK 26.0.2.1; Linux; filesystem ext4; 2 CPU budget; 512 MiB maximum heap.

<!-- benchmark-redline:end -->

### Contributor PC benchmarks

<!-- benchmark-contributor-pc:start -->
Latest contributor PC run (diagnostic, unqualified)

[Report, logs and downloadable results](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40927196794)

Contributor: Rishabh Rathi; finished 2026-10-29T18:00:51.300692+00:00; revision `c997197f28b2c53bfe5857588d12b71fe7ab93a7`.

Windows-11-10.0.26100-SP0; AMD Ryzen 5 7600X 6-Core Processor; 8 JVM active processors; 512 MiB heap; 128 MiB direct-memory limit; no OS resource quota.

JDK: openjdk version "26.0.2.1" 2026-08-18 OpenJDK Runtime Environment Temurin-26.0.2.1+1 (build 26.0.2.1+1) OpenJDK 64-Bit Server VM Temurin-26.0.2.1+1 (build 26.0.2.1+1, mixed mode, sharing). Media: Filesystem per worker; physical media not detected.

Two fresh JVMs per mode; 1 KiB records; 256-record batches; 5s minimum adaptive warmup and 10s measurement at each load. Broker and clients share the PC. These results are contributor supplied and do not qualify sustained capacity.

| Mode | Repeat | Load | Produced/s | Consumed/s | p99 ms | CPU % | Force p99 ms | Health |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 1 | 50% | 249,984 | 249,984 | 2.088 | 10.2% | 0.000 | true |
| IN_MEMORY | 1 | 80% | 400,000 | 399,949 | 2.073 | 13.4% | 0.000 | false |
| IN_MEMORY | 1 | 100% | 499,994 | 499,994 | 2.036 | 16.4% | 0.000 | true |
| FILE | 1 | 50% | 57,523 | 57,523 | 5351.932 | 31.2% | 6.394 | false |
| FILE | 1 | 80% | 56,090 | 56,115 | 7130.317 | 30.4% | 6.435 | false |
| FILE | 1 | 100% | 57,933 | 57,984 | 7587.496 | 30.5% | 6.038 | false |
| IN_MEMORY | 2 | 50% | 249,984 | 249,984 | 2.093 | 10.3% | 0.000 | false |
| IN_MEMORY | 2 | 80% | 399,949 | 399,974 | 2.058 | 14.0% | 0.000 | false |
| IN_MEMORY | 2 | 100% | 499,994 | 500,019 | 2.032 | 16.3% | 0.000 | true |
| FILE | 2 | 50% | 56,934 | 56,986 | 5377.098 | 31.9% | 6.504 | false |
| FILE | 2 | 80% | 58,035 | 58,086 | 7004.488 | 29.2% | 6.353 | false |
| FILE | 2 | 100% | 58,778 | 58,803 | 7566.524 | 30.6% | 6.169 | false |

Collected for revision `4e65282832d61710fa5e7b898842ab3f16d7386d`. Only generated benchmark README blocks and JSON/SVG snapshots differ from the measured source above; code, configuration, tools and manual documentation match.

Redline diagnostics (unqualified). One fresh JVM per mode; record bytes 1024, 256-record batches, 2s warmup and 2s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

Consumption includes broadcast fanout. Peak production is a short-window observation, not sustained capacity. The last measured window can precede the terminal failure; a CPU_HOLD row is a partial window.

| Mode | Peak produced/s | Last measured pairs | Stop stage / attempted pairs | Stop reason | Peak CPU % | Max reported heap MiB | Worst p99 ms |
| --- | ---: | ---: | --- | --- | ---: | ---: | ---: |
| IN_MEMORY | 1,177,772 | 512 | 11 / 1024 | JVM_OOM | 100.0% | 510.0 | 268.435 |
| FILE | 308,893 | 128 | 8 / 128 | BROKER_OR_CLIENT_LIMIT (INTERNAL_ERROR) | 100.0% | 498.0 | 268.435 |

| Mode | Stage | Pairs | Duration s | Produced/s | Fanout consumed/s | Average CPU % | Reported heap MiB | p99 ms | Window |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 1 | 1 | 2.01 | 1,177,772 | 1,162,632 | 38.0% | 312.0 | 0.524 | ramp |
| IN_MEMORY | 2 | 2 | 2.01 | 1,067,121 | 2,103,841 | 57.0% | 284.0 | 0.524 | ramp |
| IN_MEMORY | 3 | 4 | 2.01 | 781,305 | 3,063,654 | 98.8% | 444.0 | 1.049 | ramp |
| IN_MEMORY | 4 | 8 | 2.01 | 451,016 | 3,552,717 | 99.7% | 448.0 | 4.194 | ramp |
| IN_MEMORY | 5 | 16 | 2.01 | 259,991 | 4,046,037 | 100.0% | 392.0 | 4.194 | ramp |
| IN_MEMORY | 6 | 32 | 2.02 | 148,689 | 4,359,395 | 99.7% | 404.0 | 4.194 | ramp |
| IN_MEMORY | 7 | 64 | 2.01 | 78,955 | 4,181,549 | 100.0% | 230.0 | 8.389 | ramp |
| IN_MEMORY | 8 | 128 | 11.08 | 30,086 | 3,335,136 | 100.0% | 312.0 | 16.777 | ramp |
| IN_MEMORY | 9 | 256 | 11.09 | 14,175 | 2,908,372 | 99.8% | 510.0 | 67.109 | ramp |
| IN_MEMORY | 10 | 512 | 2.04 | 29,999 | 984,581 | 69.6% | 472.0 | 268.435 | ramp |
| FILE | 1 | 1 | 2.01 | 91,592 | 91,465 | 20.3% | 274.0 | 8.389 | ramp |
| FILE | 2 | 2 | 2.01 | 93,693 | 187,131 | 32.8% | 450.0 | 8.389 | ramp |
| FILE | 3 | 4 | 2.01 | 144,292 | 400,754 | 33.5% | 320.0 | 16.777 | ramp |
| FILE | 4 | 8 | 2.01 | 170,240 | 401,470 | 37.9% | 318.0 | 33.554 | ramp |
| FILE | 5 | 16 | 2.01 | 237,676 | 623,357 | 38.9% | 384.0 | 67.109 | ramp |
| FILE | 6 | 32 | 2.01 | 284,894 | 1,065,011 | 55.5% | 276.0 | 67.109 | ramp |
| FILE | 7 | 64 | 2.02 | 257,188 | 1,520,903 | 84.6% | 304.0 | 134.218 | ramp |
| FILE | 8 | 128 | 2.02 | 308,893 | 908,794 | 94.1% | 498.0 | 268.435 | BROKER_OR_CLIENT_LIMIT |

IN_MEMORY: JDK 26.0.2.1; Windows_11; filesystem NTFS; 8 CPU budget; 512 MiB maximum heap.
Free-disk reserve: 125.91 GiB (sampled).

FILE: JDK 26.0.2.1; Windows_11; filesystem NTFS; 8 CPU budget; 512 MiB maximum heap.
Free-disk reserve: 125.90 GiB (sampled).
<!-- benchmark-contributor-pc:end -->

The [mandatory contributor pre-push check](scripts/README.md#contributor-pc-check-before-every-push) attaches local performance evidence. CI validates the attachment and retains it with the pipeline artifacts; default-branch publication refreshes the block above independently of the CI measurements.

## Help and contributions

For setup help or a reproducible bug, start with [support and troubleshooting](SUPPORT.md). Use the [issue forms](https://github.com/RishabhRathi-Dev/Setu/issues/new/choose) for bugs and feature requests. Report vulnerabilities privately following the [security policy](SECURITY.md).

Contributions should include focused changes, relevant verification, and updated documentation. The [contributing guide](CONTRIBUTING.md) covers setup, checks, and pull requests; [AGENTS.md](AGENTS.md) records the repository map and contracts for coding agents.
