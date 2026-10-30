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
[![in-memory: 500.00K/s target, 499.99K/s CI, 100% qualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41118881914)
[![file: 250.00K/s target, 133.80K/s CI, 54% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41118881914)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest CI target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 0.41 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41118881914)
[![memory resources: 47% CPU, 254 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41118881914)
[![memory JFR: 1.66 GiB/s alloc, 7.7 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41118881914)
[![file p99: 15778.97 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41118881914)
[![file resources: 42% CPU, 122 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41118881914)
[![file JFR: 0.57 GiB/s alloc, 8.9 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41118881914)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `eed593ad825f4757751e9273d323a0b436d9d6eb`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41118881914).

Runner: INTEL(R) XEON(R) PLATINUM 8573C; 4.0 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

Runner: AMD EPYC 9V74 80-Core Processor; 4.0 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

Writes count unique records; each record needs one delivery in this workload. Write target achieved compares actual writes with requested writes. Delivery coverage compares unique deliveries with completed writes in the same window. Similar write and delivery rates do not guarantee the requested load or other limits were met.

| Mode | Reference writes/s | Median actual writes/s | Write target achieved | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 499,994 | 100.0% | 0.409 | 254 | 46.5657 | True |
| FILE | 250,000 | 133,803 | 53.5% | 15778.972 | 122 | 41.7321 | False |

**Fixed-target traffic**

| Mode | Repeat | Requested writes/s | Actual writes/s | Unique deliveries/s | Write target achieved | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 125,000 | 111,113 | 111,113 | 88.9% | 100.0% |
| FILE | 1 | 200,000 | 114,287 | 114,295 | 57.1% | 100.0% |
| FILE | 1 | 250,000 | 127,565 | 120,081 | 51.0% | 94.1% |
| FILE | 2 | 125,000 | 124,996 | 125,039 | 100.0% | 100.0% |
| FILE | 2 | 200,000 | 129,254 | 131,405 | 64.6% | 101.7% |
| FILE | 2 | 250,000 | 138,462 | 131,362 | 55.4% | 94.9% |
| FILE | 3 | 125,000 | 124,996 | 125,013 | 100.0% | 100.0% |
| FILE | 3 | 200,000 | 138,061 | 130,705 | 69.0% | 94.7% |
| FILE | 3 | 250,000 | 133,803 | 132,326 | 53.5% | 98.9% |
| IN_MEMORY | 1 | 250,000 | 249,993 | 250,001 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 400,000 | 400,000 | 400,009 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 500,000 | 499,994 | 500,002 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 250,000 | 249,993 | 250,001 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 400,000 | 400,000 | 400,009 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 500,000 | 499,994 | 500,002 | 100.0% | 100.0% |
| IN_MEMORY | 3 | 250,000 | 249,993 | 250,001 | 100.0% | 100.0% |
| IN_MEMORY | 3 | 400,000 | 400,000 | 400,009 | 100.0% | 100.0% |
| IN_MEMORY | 3 | 500,000 | 499,994 | 500,002 | 100.0% | 100.0% |

**Fixed-target latency and resource checks**

| Mode | Repeat | Load | p99 ms | CPU % | Heap slope MiB/s | Qualified window |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 3321.889 | 30.3324 | 0.006 | False |
| FILE | 1 | 80% | 12725.518 | 27.9910 | 0.157 | False |
| FILE | 1 | 100% | 15778.972 | 28.3571 | 0.266 | False |
| FILE | 2 | 50% | 5.890 | 41.1321 | 0.114 | True |
| FILE | 2 | 80% | 10930.356 | 30.5333 | 0.156 | False |
| FILE | 2 | 100% | 14638.121 | 30.2823 | 0.115 | False |
| FILE | 3 | 50% | 5.394 | 41.7321 | 0.066 | True |
| FILE | 3 | 80% | 10913.579 | 30.5070 | 0.172 | False |
| FILE | 3 | 100% | 14604.567 | 29.7576 | 0.247 | False |
| IN_MEMORY | 1 | 50% | 0.362 | 33.4406 | 0.130 | True |
| IN_MEMORY | 1 | 80% | 0.366 | 41.4655 | 0.425 | True |
| IN_MEMORY | 1 | 100% | 0.409 | 46.4910 | 0.044 | True |
| IN_MEMORY | 2 | 50% | 0.435 | 33.7827 | -0.053 | True |
| IN_MEMORY | 2 | 80% | 0.368 | 41.6071 | 0.250 | True |
| IN_MEMORY | 2 | 100% | 0.402 | 46.5657 | 0.190 | True |
| IN_MEMORY | 3 | 50% | 0.383 | 33.4000 | 0.032 | True |
| IN_MEMORY | 3 | 80% | 0.382 | 41.5402 | 0.488 | True |
| IN_MEMORY | 3 | 100% | 0.402 | 46.5148 | 0.124 | True |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | throughput 111,113/s <95% of offered 125,000/s; p99 3321.889 ms outside [0,10) ms |
| FILE | 1 | 80% | throughput 114,287/s <95% of offered 200,000/s; p99 12725.518 ms outside [0,100) ms |
| FILE | 1 | 100% | consumption 94.1% <95%; throughput 127,565/s <95% of offered 250,000/s; p99 15778.972 ms outside [0,100) ms |
| FILE | 2 | 80% | throughput 129,254/s <95% of offered 200,000/s; p99 10930.356 ms outside [0,100) ms |
| FILE | 2 | 100% | consumption 94.9% <95%; throughput 138,462/s <95% of offered 250,000/s; p99 14638.121 ms outside [0,100) ms |
| FILE | 3 | 80% | consumption 94.7% <95%; throughput 138,061/s <95% of offered 200,000/s; p99 10913.579 ms outside [0,100) ms |
| FILE | 3 | 100% | throughput 133,803/s <95% of offered 250,000/s; p99 14604.567 ms outside [0,100) ms |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 27.607 | 0.965 | 23.855 | 3321.889 |
| FILE | 1 | 80% | 27.427 | 0.890 | 24.068 | 12725.518 |
| FILE | 1 | 100% | 26.132 | 0.930 | 22.495 | 15778.972 |
| FILE | 2 | 50% | 3.951 | 0.506 | 3.459 | 5.890 |
| FILE | 2 | 80% | 5.820 | 0.949 | 5.550 | 10930.356 |
| FILE | 2 | 100% | 5.644 | 0.945 | 5.513 | 14638.121 |
| FILE | 3 | 50% | 3.946 | 0.526 | 3.506 | 5.394 |
| FILE | 3 | 80% | 5.640 | 0.990 | 5.526 | 10913.579 |
| FILE | 3 | 100% | 5.730 | 0.924 | 5.521 | 14604.567 |
| IN_MEMORY | 1 | 50% | 0.290 | 0.206 | 0.111 | 0.362 |
| IN_MEMORY | 1 | 80% | 0.277 | 0.214 | 0.122 | 0.366 |
| IN_MEMORY | 1 | 100% | 0.290 | 0.245 | 0.172 | 0.409 |
| IN_MEMORY | 2 | 50% | 0.325 | 0.212 | 0.157 | 0.435 |
| IN_MEMORY | 2 | 80% | 0.278 | 0.217 | 0.121 | 0.368 |
| IN_MEMORY | 2 | 100% | 0.288 | 0.247 | 0.169 | 0.402 |
| IN_MEMORY | 3 | 50% | 0.302 | 0.217 | 0.115 | 0.383 |
| IN_MEMORY | 3 | 80% | 0.289 | 0.244 | 0.130 | 0.382 |
| IN_MEMORY | 3 | 100% | 0.292 | 0.265 | 0.171 | 0.402 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 25194 | 65925.316720 | 19.841023 | 210.675343 |
| FILE | 1 | 2 | 25787 | 66729.110150 | 15.171583 | 30.339203 |
| FILE | 1 | 3 | 28311 | 65770.763201 | 5.062655 | 28.976555 |
| FILE | 2 | 1 | 24254 | 35342.142622 | 2.840575 | 16.907989 |
| FILE | 2 | 2 | 29725 | 66761.910184 | 4.808703 | 7.917562 |
| FILE | 2 | 3 | 30735 | 65595.600646 | 4.468735 | 10.251604 |
| FILE | 3 | 1 | 24050 | 35019.428357 | 2.818047 | 15.763709 |
| FILE | 3 | 2 | 30729 | 65694.580854 | 4.464639 | 10.825510 |
| FILE | 3 | 3 | 30356 | 66107.019898 | 4.616191 | 10.753693 |
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
Source revision `eed593ad825f4757751e9273d323a0b436d9d6eb`. [CI redline report and captures](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41118881914)

Resource-enforced redline diagnostics (unqualified)

Source revision `eed593ad825f4757751e9273d323a0b436d9d6eb`; enforcement: systemd-cgroup-v2.

Host: AMD EPYC 9V74 80-Core Processor; filesystem/media: /dev/nvme0n1p1 ext4 (non-rotational). Allocation: 2 CPU cores, 6554 MiB process-tree memory; 512 MiB worker heap, 128 MiB direct memory; free-disk reserve 17.03 GiB.

Execution exit code: 0.

**Redline diagnostics: find a runtime limit (unqualified)**

**Unique writes/s** counts records appended once. This is a fanout workload: every consumer reads the same partition, so one write creates one delivery per consumer. **Deliveries needed/s = unique writes/s × consumers.** **Delivery coverage** is completed deliveries divided by (written records × consumers) within the same window.

For example, 100,000 writes/s with 8 consumers needs 800,000 deliveries/s. 400,000 completed deliveries/s covers 50% of that demand.

Coverage describes aggregate progress during the measurement window, not a record-loss rate or proof that every consumer caught up. It can slightly exceed 100% when operations cross a window boundary; n/a means no writes completed. A completed load step is not a health or capacity pass. Peak production is a short-window observation, not sustained capacity.

One fresh JVM per mode; record bytes 1024, 256-record batches, 2s warmup and 2s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

| Mode | Highest unique writes/s | Consumers at that rate | Delivery coverage at that rate | Why the test stopped |
| --- | ---: | ---: | ---: | --- |
| FILE | 158,087 | 16 | 22.6% | Broker/client limit reached (BROKER_OR_CLIENT_LIMIT) |

**FILE: delivery progress**

The last measured stage (9, 256 consumers) covered **0.7%** of its delivery demand. The test stopped at stage 9 with 256 producers and 256 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 63,106 | 63,106 | 61,445 | 97.4% |
| 2 | 2 | 63,233 | 126,466 | 110,626 | 87.5% |
| 3 | 4 | 54,546 | 218,184 | 118,800 | 54.4% |
| 4 | 8 | 152,203 | 1,217,624 | 408,726 | 33.6% |
| 5 | 16 | 158,087 | 2,529,392 | 571,309 | 22.6% |
| 6 | 32 | 110,321 | 3,530,272 | 344,114 | 9.7% |
| 7 | 64 | 157,575 | 10,084,800 | 534,657 | 5.3% |
| 8 | 128 | 109,579 | 14,026,112 | 266,557 | 1.9% |
| 9 | 256 | 63,106 | 16,155,136 | 119,457 | 0.7% |

**FILE: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 2.00 | 59.4% | 114.0 | 134.218 | Measured |
| 2 | 2.00 | 55.9% | 314.0 | 268.435 | Measured |
| 3 | 2.00 | 39.4% | 76.0 | 268.435 | Measured |
| 4 | 2.00 | 78.3% | 294.0 | 33.554 | Measured |
| 5 | 2.00 | 73.4% | 186.0 | 33.554 | Measured |
| 6 | 2.00 | 43.9% | 214.0 | 536.871 | Measured |
| 7 | 2.00 | 66.9% | 358.0 | 268.435 | Measured |
| 8 | 2.10 | 94.6% | 382.0 | 536.871 | Measured |
| 9 | 2.99 | 96.7% | 506.0 | 2147.484 | Broker/client limit reached |

FILE: JDK 26.0.2.1; Linux; filesystem ext4; CPU percentages use 2 cores as 100%; 512 MiB maximum heap.


Resource-enforced redline diagnostics (unqualified)

Source revision `eed593ad825f4757751e9273d323a0b436d9d6eb`; enforcement: systemd-cgroup-v2.

Host: AMD EPYC 7763 64-Core Processor; filesystem/media: /dev/sda1 ext4 (rotational). Allocation: 2 CPU cores, 6554 MiB process-tree memory; 512 MiB worker heap, 128 MiB direct memory; free-disk reserve 17.03 GiB.

Execution exit code: 0.

**Redline diagnostics: find a runtime limit (unqualified)**

**Unique writes/s** counts records appended once. This is a fanout workload: every consumer reads the same partition, so one write creates one delivery per consumer. **Deliveries needed/s = unique writes/s × consumers.** **Delivery coverage** is completed deliveries divided by (written records × consumers) within the same window.

For example, 100,000 writes/s with 8 consumers needs 800,000 deliveries/s. 400,000 completed deliveries/s covers 50% of that demand.

Coverage describes aggregate progress during the measurement window, not a record-loss rate or proof that every consumer caught up. It can slightly exceed 100% when operations cross a window boundary; n/a means no writes completed. A completed load step is not a health or capacity pass. Peak production is a short-window observation, not sustained capacity.

One fresh JVM per mode; record bytes 1024, 256-record batches, 2s warmup and 2s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

| Mode | Highest unique writes/s | Consumers at that rate | Delivery coverage at that rate | Why the test stopped |
| --- | ---: | ---: | ---: | --- |
| IN_MEMORY | 267,530 | 1 | 94.3% | JVM heap exhausted (JVM_OOM) |

**IN_MEMORY: delivery progress**

The last measured stage (11, 1024 consumers) covered **0.8%** of its delivery demand. The test stopped at stage 12 with 2048 producers and 2048 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 267,530 | 267,530 | 252,334 | 94.3% |
| 2 | 2 | 241,475 | 482,950 | 452,593 | 93.7% |
| 3 | 4 | 187,576 | 750,304 | 727,045 | 96.9% |
| 4 | 8 | 131,852 | 1,054,816 | 1,021,374 | 96.8% |
| 5 | 16 | 71,985 | 1,151,760 | 1,073,920 | 93.2% |
| 6 | 32 | 41,565 | 1,330,080 | 1,225,861 | 92.2% |
| 7 | 64 | 20,668 | 1,322,752 | 1,163,888 | 88.0% |
| 8 | 128 | 8,378 | 1,072,384 | 1,026,700 | 95.7% |
| 9 | 256 | 23,294 | 5,963,264 | 773,752 | 13.0% |
| 10 | 512 | 21,133 | 10,820,096 | 714,746 | 6.6% |
| 11 | 1024 | 28,661 | 29,348,864 | 248,470 | 0.8% |

**IN_MEMORY: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 2.00 | 94.4% | 178.0 | 4.194 | Measured |
| 2 | 2.05 | 95.1% | 256.0 | 4.194 | Measured |
| 3 | 2.03 | 95.9% | 240.0 | 33.554 | Measured |
| 4 | 2.01 | 97.2% | 266.0 | 33.554 | Measured |
| 5 | 2.01 | 94.5% | 308.0 | 67.109 | Measured |
| 6 | 2.01 | 97.0% | 298.0 | 67.109 | Measured |
| 7 | 2.01 | 96.4% | 216.0 | 67.109 | Measured |
| 8 | 11.92 | 95.9% | 416.0 | 134.218 | Measured |
| 9 | 2.08 | 94.9% | 354.0 | 134.218 | Measured |
| 10 | 2.04 | 93.7% | 482.0 | 268.435 | Measured |
| 11 | 2.19 | 88.5% | 506.0 | 1073.742 | Measured |

IN_MEMORY: JDK 26.0.2.1; Linux; filesystem ext4; CPU percentages use 2 cores as 100%; 512 MiB maximum heap.

<!-- benchmark-redline:end -->

### Contributor PC benchmarks

<!-- benchmark-contributor-pc:start -->
Latest contributor PC run (diagnostic, unqualified)

[Report, logs and downloadable results](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41118881914)

Contributor: Rishabh Rathi; finished 2026-10-30T18:21:03.501974+00:00; revision `19d1bf62a25b355ca437704b7de06229bfc8bdc6`.

Windows-11-10.0.26100-SP0; AMD Ryzen 5 7600X 6-Core Processor; 8 JVM active processors; 512 MiB heap; 128 MiB direct-memory limit; no OS resource quota.

JDK: openjdk version "26.0.2.1" 2026-08-18 OpenJDK Runtime Environment Temurin-26.0.2.1+1 (build 26.0.2.1+1) OpenJDK 64-Bit Server VM Temurin-26.0.2.1+1 (build 26.0.2.1+1, mixed mode, sharing). Media: Filesystem per worker; physical media not detected.

Two fresh JVMs per mode; 1 KiB records; 256-record batches; 5s minimum adaptive warmup and 10s measurement at each load. Broker and clients share the PC. These results are contributor supplied and do not qualify sustained capacity.

**Fixed target: requested writes versus completed work**

These windows count unique records, with one delivery per record. Requested writes/s is the offered load; actual writes/s is the completed rate. Write target achieved compares actual with requested writes. Delivery coverage compares unique deliveries with completed writes. Consumers keeping up does not mean the write target was met.

| Mode | Repeat | Requested writes/s | Actual writes/s | Unique deliveries/s | Write target achieved | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| IN_MEMORY | 1 | 250,000 | 249,984 | 250,010 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 400,000 | 400,000 | 400,026 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 500,000 | 499,994 | 500,019 | 100.0% | 100.0% |
| FILE | 1 | 125,000 | 38,067 | 38,118 | 30.5% | 100.1% |
| FILE | 1 | 200,000 | 38,426 | 38,477 | 19.2% | 100.1% |
| FILE | 1 | 250,000 | 37,018 | 37,069 | 14.8% | 100.1% |
| IN_MEMORY | 2 | 250,000 | 249,984 | 250,035 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 400,000 | 400,000 | 400,026 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 500,000 | 499,994 | 500,019 | 100.0% | 100.0% |
| FILE | 2 | 125,000 | 34,150 | 34,202 | 27.3% | 100.1% |
| FILE | 2 | 200,000 | 37,402 | 37,453 | 18.7% | 100.1% |
| FILE | 2 | 250,000 | 37,197 | 37,222 | 14.9% | 100.1% |

**Fixed target: latency and resource checks**

Window checks include throughput, consumption, latency, CPU and heap limits. A passed window remains a local diagnostic, not qualified sustained capacity.

| Mode | Repeat | Load | p99 ms | CPU budget used | Force p99 ms | Window checks |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 1 | 50% | 1.894 | 12.1% | 0.000 | Passed |
| IN_MEMORY | 1 | 80% | 1.846 | 15.8% | 0.000 | Missed |
| IN_MEMORY | 1 | 100% | 1.730 | 20.0% | 0.000 | Passed |
| FILE | 1 | 50% | 6874.464 | 31.4% | 8.311 | Missed |
| FILE | 1 | 80% | 7981.761 | 31.4% | 8.389 | Missed |
| FILE | 1 | 100% | 8434.745 | 30.0% | 8.864 | Missed |
| IN_MEMORY | 2 | 50% | 1.769 | 12.3% | 0.000 | Missed |
| IN_MEMORY | 2 | 80% | 1.687 | 16.1% | 0.000 | Passed |
| IN_MEMORY | 2 | 100% | 1.729 | 19.5% | 0.000 | Missed |
| FILE | 2 | 50% | 7201.620 | 31.4% | 9.429 | Missed |
| FILE | 2 | 80% | 8040.481 | 31.2% | 9.142 | Missed |
| FILE | 2 | 100% | 8422.162 | 30.5% | 8.995 | Missed |

**Redline diagnostics: find a runtime limit (unqualified)**

**Unique writes/s** counts records appended once. This is a fanout workload: every consumer reads the same partition, so one write creates one delivery per consumer. **Deliveries needed/s = unique writes/s × consumers.** **Delivery coverage** is completed deliveries divided by (written records × consumers) within the same window.

For example, 100,000 writes/s with 8 consumers needs 800,000 deliveries/s. 400,000 completed deliveries/s covers 50% of that demand.

Coverage describes aggregate progress during the measurement window, not a record-loss rate or proof that every consumer caught up. It can slightly exceed 100% when operations cross a window boundary; n/a means no writes completed. A completed load step is not a health or capacity pass. Peak production is a short-window observation, not sustained capacity.

One fresh JVM per mode; record bytes 1024, 256-record batches, 2s warmup and 2s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

| Mode | Highest unique writes/s | Consumers at that rate | Delivery coverage at that rate | Why the test stopped |
| --- | ---: | ---: | ---: | --- |
| IN_MEMORY | 843,380 | 1 | 98.2% | JVM heap exhausted (JVM_OOM) |
| FILE | 279,486 | 128 | 2.2% | Broker/client limit reached (BROKER_OR_CLIENT_LIMIT) |

**IN_MEMORY: delivery progress**

The last measured stage (10, 512 consumers) covered **5.8%** of its delivery demand. The test stopped at stage 11 with 1024 producers and 1024 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 843,380 | 843,380 | 828,093 | 98.2% |
| 2 | 2 | 633,669 | 1,267,338 | 1,236,214 | 97.5% |
| 3 | 4 | 570,278 | 2,281,112 | 2,242,787 | 98.3% |
| 4 | 8 | 367,587 | 2,940,696 | 2,858,106 | 97.2% |
| 5 | 16 | 216,743 | 3,467,888 | 3,309,062 | 95.4% |
| 6 | 32 | 123,594 | 3,955,008 | 3,425,777 | 86.6% |
| 7 | 64 | 67,035 | 4,290,240 | 3,112,290 | 72.5% |
| 8 | 128 | 25,448 | 3,257,344 | 2,714,644 | 83.3% |
| 9 | 256 | 28,504 | 7,297,024 | 2,216,360 | 30.4% |
| 10 | 512 | 30,941 | 15,841,792 | 912,567 | 5.8% |

**IN_MEMORY: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 2.01 | 36.5% | 244.0 | 1.049 | Measured |
| 2 | 2.02 | 53.5% | 416.0 | 1.049 | Measured |
| 3 | 2.02 | 79.0% | 364.0 | 2.097 | Measured |
| 4 | 2.01 | 91.9% | 382.0 | 4.194 | Measured |
| 5 | 2.02 | 91.6% | 362.0 | 4.194 | Measured |
| 6 | 2.02 | 93.0% | 228.0 | 8.389 | Measured |
| 7 | 2.04 | 86.5% | 284.0 | 16.777 | Measured |
| 8 | 11.91 | 93.7% | 318.0 | 33.554 | Measured |
| 9 | 2.02 | 84.5% | 390.0 | 67.109 | Measured |
| 10 | 2.03 | 64.0% | 490.0 | 268.435 | Measured |

IN_MEMORY: JDK 26.0.2.1; Windows_11; filesystem NTFS; CPU percentages use 8 cores as 100%; 512 MiB maximum heap.
Free-disk reserve: 122.58 GiB (sampled).

**FILE: delivery progress**

The last measured stage (9, 256 consumers) covered **0.8%** of its delivery demand. The test stopped at stage 9 with 256 producers and 256 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 74,562 | 74,562 | 74,562 | 100.0% |
| 2 | 2 | 80,560 | 161,120 | 161,120 | 100.0% |
| 3 | 4 | 98,545 | 394,180 | 393,417 | 99.8% |
| 4 | 8 | 108,130 | 865,040 | 454,527 | 52.5% |
| 5 | 16 | 136,499 | 2,183,984 | 579,547 | 26.5% |
| 6 | 32 | 177,646 | 5,684,672 | 1,047,606 | 18.4% |
| 7 | 64 | 220,840 | 14,133,760 | 1,117,869 | 7.9% |
| 8 | 128 | 279,486 | 35,774,208 | 780,038 | 2.2% |
| 9 | 256 | 136,167 | 34,858,752 | 278,386 | 0.8% |

**FILE: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 2.01 | 21.4% | 284.0 | 8.389 | Measured |
| 2 | 2.01 | 33.2% | 238.0 | 16.777 | Measured |
| 3 | 2.02 | 40.5% | 298.0 | 33.554 | Measured |
| 4 | 2.01 | 37.6% | 410.0 | 33.554 | Measured |
| 5 | 2.01 | 35.1% | 174.0 | 67.109 | Measured |
| 6 | 2.02 | 52.0% | 158.0 | 134.218 | Measured |
| 7 | 2.02 | 70.2% | 260.0 | 134.218 | Measured |
| 8 | 2.03 | 81.6% | 490.0 | 268.435 | Measured |
| 9 | 0.25 | 0.0% | 0.0 | 268.435 | Broker/client limit reached |

FILE: JDK 26.0.2.1; Windows_11; filesystem NTFS; CPU percentages use 8 cores as 100%; 512 MiB maximum heap.
Free-disk reserve: 122.58 GiB (sampled).
<!-- benchmark-contributor-pc:end -->

The [mandatory contributor pre-push check](scripts/README.md#contributor-pc-check-before-every-push) attaches local performance evidence. CI validates the attachment and retains it with the pipeline artifacts; default-branch publication refreshes the block above independently of the CI measurements.

## Help and contributions

For setup help or a reproducible bug, start with [support and troubleshooting](SUPPORT.md). Use the [issue forms](https://github.com/RishabhRathi-Dev/Setu/issues/new/choose) for bugs and feature requests. Report vulnerabilities privately following the [security policy](SECURITY.md).

Contributions should include focused changes, relevant verification, and updated documentation. The [contributing guide](CONTRIBUTING.md) covers setup, checks, and pull requests; [AGENTS.md](AGENTS.md) records the repository map and contracts for coding agents.
