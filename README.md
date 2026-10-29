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
[![in-memory: 500.00K/s target, 499.99K/s CI, 100% qualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40917165015)
[![file: 250.00K/s target, 166.24K/s CI, 66% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40917165015)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest CI target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 0.74 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40917165015)
[![memory resources: 58% CPU, 254 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40917165015)
[![memory JFR: 1.65 GiB/s alloc, 9.8 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40917165015)
[![file p99: 10057.94 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40917165015)
[![file resources: 34% CPU, 126 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40917165015)
[![file JFR: 0.72 GiB/s alloc, 11.3 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40917165015)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `70cc5024fd997d4661e7875bbe5f6550e26da466`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40917165015).

Runner: AMD EPYC 9V45 96-Core Processor; 4.0 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

Runner: AMD EPYC 7763 64-Core Processor; 4.0 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

| Mode | Target/s | Median measured/s | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 499,994 | 0.738 | 254 | 57.8646 | True |
| FILE | 250,000 | 166,238 | 10057.941 | 126 | 34.1903 | False |

| Mode | Repeat | Load | Measured/s | Consumed/s | p99 ms | CPU % | Heap slope MiB/s | Healthy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 124,996 | 125,030 | 472.383 | 32.2575 | -0.106 | False |
| FILE | 1 | 80% | 165,538 | 165,564 | 5117.051 | 21.2910 | -0.093 | False |
| FILE | 1 | 100% | 165,376 | 165,350 | 10057.941 | 21.0583 | 0.121 | False |
| FILE | 2 | 50% | 124,996 | 125,013 | 2.570 | 34.1903 | -0.053 | True |
| FILE | 2 | 80% | 165,598 | 165,623 | 5112.857 | 20.7656 | -0.053 | False |
| FILE | 2 | 100% | 166,238 | 166,221 | 9957.278 | 20.0159 | 0.044 | False |
| FILE | 3 | 50% | 124,996 | 125,022 | 2.169 | 33.4325 | -0.052 | True |
| FILE | 3 | 80% | 188,698 | 180,966 | 2625.634 | 22.0827 | -0.020 | False |
| FILE | 3 | 100% | 198,187 | 191,693 | 6161.433 | 22.3416 | -0.037 | False |
| IN_MEMORY | 1 | 50% | 249,993 | 250,086 | 0.634 | 43.2661 | 0.025 | True |
| IN_MEMORY | 1 | 80% | 400,000 | 400,009 | 0.614 | 52.4570 | 0.284 | True |
| IN_MEMORY | 1 | 100% | 499,994 | 500,002 | 0.706 | 57.8646 | 0.313 | True |
| IN_MEMORY | 2 | 50% | 249,993 | 250,052 | 0.634 | 42.7737 | 0.232 | True |
| IN_MEMORY | 2 | 80% | 400,000 | 400,000 | 0.612 | 52.5149 | 0.591 | True |
| IN_MEMORY | 2 | 100% | 499,994 | 500,002 | 0.690 | 57.7810 | 0.410 | True |
| IN_MEMORY | 3 | 50% | 249,993 | 250,078 | 0.578 | 42.4985 | 0.060 | True |
| IN_MEMORY | 3 | 80% | 400,000 | 400,009 | 0.649 | 52.1648 | 0.458 | True |
| IN_MEMORY | 3 | 100% | 499,994 | 500,002 | 0.738 | 57.4983 | 0.014 | True |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | p99 472.383 ms outside [0,10) ms |
| FILE | 1 | 80% | throughput 165,538/s <95% of offered 200,000/s; p99 5117.051 ms outside [0,100) ms |
| FILE | 1 | 100% | throughput 165,376/s <95% of offered 250,000/s; p99 10057.941 ms outside [0,100) ms |
| FILE | 2 | 80% | throughput 165,598/s <95% of offered 200,000/s; p99 5112.857 ms outside [0,100) ms |
| FILE | 2 | 100% | throughput 166,238/s <95% of offered 250,000/s; p99 9957.278 ms outside [0,100) ms |
| FILE | 3 | 80% | throughput 188,698/s <95% of offered 200,000/s; p99 2625.634 ms outside [0,100) ms |
| FILE | 3 | 100% | throughput 198,187/s <95% of offered 250,000/s; p99 6161.433 ms outside [0,100) ms |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 7.991 | 0.319 | 7.504 | 472.383 |
| FILE | 1 | 80% | 8.397 | 0.679 | 8.069 | 5117.051 |
| FILE | 1 | 100% | 8.479 | 0.676 | 8.131 | 10057.941 |
| FILE | 2 | 50% | 2.136 | 0.307 | 1.484 | 2.570 |
| FILE | 2 | 80% | 8.487 | 0.717 | 8.110 | 5112.857 |
| FILE | 2 | 100% | 8.430 | 0.628 | 8.118 | 9957.278 |
| FILE | 3 | 50% | 1.917 | 0.303 | 1.167 | 2.169 |
| FILE | 3 | 80% | 8.294 | 0.938 | 7.991 | 2625.634 |
| FILE | 3 | 100% | 8.024 | 0.852 | 7.778 | 6161.433 |
| IN_MEMORY | 1 | 50% | 0.453 | 0.342 | 0.195 | 0.634 |
| IN_MEMORY | 1 | 80% | 0.438 | 0.379 | 0.254 | 0.614 |
| IN_MEMORY | 1 | 100% | 0.480 | 0.425 | 0.281 | 0.706 |
| IN_MEMORY | 2 | 50% | 0.459 | 0.327 | 0.198 | 0.634 |
| IN_MEMORY | 2 | 80% | 0.431 | 0.369 | 0.253 | 0.612 |
| IN_MEMORY | 2 | 100% | 0.474 | 0.409 | 0.279 | 0.690 |
| IN_MEMORY | 3 | 50% | 0.437 | 0.323 | 0.183 | 0.578 |
| IN_MEMORY | 3 | 80% | 0.438 | 0.378 | 0.268 | 0.649 |
| IN_MEMORY | 3 | 100% | 0.476 | 0.415 | 0.278 | 0.738 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 22607 | 15198.018327 | 7.450623 | 223.943362 |
| FILE | 1 | 2 | 31289 | 70547.727765 | 7.917567 | 18.962554 |
| FILE | 1 | 3 | 31276 | 69982.448654 | 7.970815 | 24.580510 |
| FILE | 2 | 1 | 22594 | 9413.739229 | 1.080319 | 18.551162 |
| FILE | 2 | 2 | 31281 | 70583.615555 | 8.011775 | 18.656616 |
| FILE | 2 | 3 | 31184 | 70603.438378 | 7.983103 | 32.714422 |
| FILE | 3 | 1 | 22598 | 9101.743138 | 0.872447 | 18.178218 |
| FILE | 3 | 2 | 33705 | 68782.064136 | 7.839743 | 40.532874 |
| FILE | 3 | 3 | 35436 | 67566.820386 | 7.548927 | 16.873838 |
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

### Contributor PC benchmarks

<!-- benchmark-contributor-pc:start -->
Latest contributor PC run (diagnostic, unqualified)

[Report, logs and downloadable results](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40917165015)

Contributor: Rishabh Rathi; finished 2026-10-29T16:59:11.830349+00:00; revision `15d3fece4152c02ede0a415f3ae3f99967aec281`.

Windows-11-10.0.26100-SP0; AMD Ryzen 5 7600X 6-Core Processor; 8 JVM active processors; 512 MiB heap; 128 MiB direct-memory limit; no OS resource quota.

JDK: openjdk version "26.0.2.1" 2026-08-18 OpenJDK Runtime Environment Temurin-26.0.2.1+1 (build 26.0.2.1+1) OpenJDK 64-Bit Server VM Temurin-26.0.2.1+1 (build 26.0.2.1+1, mixed mode, sharing). Media: Filesystem per worker; physical media not detected.

Two fresh JVMs per mode; 1 KiB records; 256-record batches; 5s minimum adaptive warmup and 10s measurement at each load. Broker and clients share the PC. These results are contributor supplied and do not qualify sustained capacity.

| Mode | Repeat | Load | Produced/s | Consumed/s | p99 ms | CPU % | Force p99 ms | Health |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 1 | 50% | 249,984 | 250,010 | 2.082 | 10.0% | 0.000 | true |
| IN_MEMORY | 1 | 80% | 399,974 | 399,974 | 2.062 | 12.9% | 0.000 | true |
| IN_MEMORY | 1 | 100% | 499,994 | 500,019 | 2.047 | 14.3% | 0.000 | true |
| FILE | 1 | 50% | 52,582 | 52,634 | 5725.225 | 31.5% | 7.393 | false |
| FILE | 1 | 80% | 54,810 | 54,861 | 7134.511 | 29.7% | 7.033 | false |
| FILE | 1 | 100% | 57,446 | 57,498 | 7612.662 | 29.8% | 6.492 | false |
| IN_MEMORY | 2 | 50% | 249,984 | 249,984 | 2.080 | 8.5% | 0.000 | true |
| IN_MEMORY | 2 | 80% | 399,949 | 400,000 | 2.062 | 12.0% | 0.000 | false |
| IN_MEMORY | 2 | 100% | 499,968 | 500,019 | 2.043 | 17.0% | 0.000 | true |
| FILE | 2 | 50% | 56,294 | 56,320 | 5414.846 | 31.4% | 6.406 | false |
| FILE | 2 | 80% | 50,816 | 50,842 | 7390.364 | 29.4% | 7.152 | false |
| FILE | 2 | 100% | 52,762 | 52,813 | 7813.988 | 29.5% | 7.156 | false |
<!-- benchmark-contributor-pc:end -->

The [mandatory contributor pre-push check](scripts/README.md#contributor-pc-check-before-every-push) attaches local performance evidence. CI validates the attachment and retains it with the pipeline artifacts; default-branch publication refreshes the block above independently of the CI measurements.

## Help and contributions

For setup help or a reproducible bug, start with [support and troubleshooting](SUPPORT.md). Use the [issue forms](https://github.com/RishabhRathi-Dev/Setu/issues/new/choose) for bugs and feature requests. Report vulnerabilities privately following the [security policy](SECURITY.md).

Contributions should include focused changes, relevant verification, and updated documentation. The [contributing guide](CONTRIBUTING.md) covers setup, checks, and pull requests; [AGENTS.md](AGENTS.md) records the repository map and contracts for coding agents.
