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
[![in-memory: 500.00K/s target, 499.99K/s CI, 100% qualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41127515368)
[![file: 250.00K/s target, 145.23K/s CI, 58% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41127515368)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest CI target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 0.62 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41127515368)
[![memory resources: 50% CPU, 252 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41127515368)
[![memory JFR: 1.65 GiB/s alloc, 11.6 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41127515368)
[![file p99: 14327.74 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41127515368)
[![file resources: 37% CPU, 126 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41127515368)
[![file JFR: 0.63 GiB/s alloc, 7.5 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41127515368)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `33190572a4385e9df0932cb4cdfcc16db61bc26e`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41127515368).

Runner: AMD EPYC 9V74 80-Core Processor; 4.0 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

Runner: INTEL(R) XEON(R) PLATINUM 8573C; 4.0 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

Writes count unique records; each record needs one delivery in this workload. Write target achieved compares actual writes with requested writes. Delivery coverage compares unique deliveries with completed writes in the same window. Similar write and delivery rates do not guarantee the requested load or other limits were met.

| Mode | Reference writes/s | Median actual writes/s | Write target achieved | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 499,994 | 100.0% | 0.623 | 252 | 49.9399 | True |
| FILE | 250,000 | 145,229 | 58.1% | 14327.742 | 126 | 37.4734 | False |

**Fixed-target traffic**

| Mode | Repeat | Requested writes/s | Actual writes/s | Unique deliveries/s | Write target achieved | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 125,000 | 124,996 | 125,013 | 100.0% | 100.0% |
| FILE | 1 | 200,000 | 128,503 | 130,270 | 64.3% | 101.4% |
| FILE | 1 | 250,000 | 146,381 | 146,355 | 58.6% | 100.0% |
| FILE | 2 | 125,000 | 124,996 | 125,013 | 100.0% | 100.0% |
| FILE | 2 | 200,000 | 138,052 | 138,052 | 69.0% | 100.0% |
| FILE | 2 | 250,000 | 145,229 | 145,237 | 58.1% | 100.0% |
| FILE | 3 | 125,000 | 115,379 | 117,018 | 92.3% | 101.4% |
| FILE | 3 | 200,000 | 116,873 | 116,873 | 58.4% | 100.0% |
| FILE | 3 | 250,000 | 141,244 | 136,149 | 56.5% | 96.4% |
| IN_MEMORY | 1 | 250,000 | 249,993 | 250,052 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 400,000 | 400,000 | 400,009 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 500,000 | 499,994 | 500,002 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 250,000 | 249,993 | 250,069 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 400,000 | 400,000 | 400,009 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 500,000 | 499,994 | 499,994 | 100.0% | 100.0% |
| IN_MEMORY | 3 | 250,000 | 249,993 | 250,001 | 100.0% | 100.0% |
| IN_MEMORY | 3 | 400,000 | 400,000 | 400,000 | 100.0% | 100.0% |
| IN_MEMORY | 3 | 500,000 | 499,994 | 500,002 | 100.0% | 100.0% |

**Fixed-target latency and resource checks**

| Mode | Repeat | Load | p99 ms | CPU % | Heap slope MiB/s | Qualified window |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 545.260 | 35.0242 | -0.014 | False |
| FILE | 1 | 80% | 11257.512 | 23.5077 | 0.363 | False |
| FILE | 1 | 100% | 12339.642 | 26.0488 | 0.021 | False |
| FILE | 2 | 50% | 481.821 | 37.4734 | -0.103 | False |
| FILE | 2 | 80% | 9261.023 | 26.8246 | -0.062 | False |
| FILE | 2 | 100% | 12473.860 | 27.1572 | 0.077 | False |
| FILE | 3 | 50% | 2717.909 | 29.3404 | 0.000 | False |
| FILE | 3 | 80% | 12423.528 | 24.7992 | -0.034 | False |
| FILE | 3 | 100% | 14327.742 | 22.3750 | 0.132 | False |
| IN_MEMORY | 1 | 50% | 0.507 | 34.5986 | -0.002 | True |
| IN_MEMORY | 1 | 80% | 0.503 | 43.1821 | 0.356 | True |
| IN_MEMORY | 1 | 100% | 0.571 | 49.7328 | 0.231 | True |
| IN_MEMORY | 2 | 50% | 0.496 | 34.6238 | 0.485 | True |
| IN_MEMORY | 2 | 80% | 0.527 | 43.3406 | 0.573 | True |
| IN_MEMORY | 2 | 100% | 0.623 | 49.7992 | 0.437 | True |
| IN_MEMORY | 3 | 50% | 0.505 | 34.7077 | 0.177 | True |
| IN_MEMORY | 3 | 80% | 0.546 | 43.3410 | 0.188 | True |
| IN_MEMORY | 3 | 100% | 0.595 | 49.9399 | 0.412 | True |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | p99 545.260 ms outside [0,10) ms |
| FILE | 1 | 80% | throughput 128,503/s <95% of offered 200,000/s; p99 11257.512 ms outside [0,100) ms |
| FILE | 1 | 100% | throughput 146,381/s <95% of offered 250,000/s; p99 12339.642 ms outside [0,100) ms |
| FILE | 2 | 50% | p99 481.821 ms outside [0,10) ms |
| FILE | 2 | 80% | throughput 138,052/s <95% of offered 200,000/s; p99 9261.023 ms outside [0,100) ms |
| FILE | 2 | 100% | throughput 145,229/s <95% of offered 250,000/s; p99 12473.860 ms outside [0,100) ms |
| FILE | 3 | 50% | throughput 115,379/s <95% of offered 125,000/s; p99 2717.909 ms outside [0,10) ms |
| FILE | 3 | 80% | throughput 116,873/s <95% of offered 200,000/s; p99 12423.528 ms outside [0,100) ms |
| FILE | 3 | 100% | throughput 141,244/s <95% of offered 250,000/s; p99 14327.742 ms outside [0,100) ms |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 6.230 | 0.446 | 5.399 | 545.260 |
| FILE | 1 | 80% | 13.017 | 0.625 | 6.386 | 11257.512 |
| FILE | 1 | 100% | 8.380 | 0.626 | 5.779 | 12339.642 |
| FILE | 2 | 50% | 5.894 | 0.390 | 4.010 | 481.821 |
| FILE | 2 | 80% | 7.524 | 0.606 | 5.771 | 9261.023 |
| FILE | 2 | 100% | 6.238 | 0.608 | 5.652 | 12473.860 |
| FILE | 3 | 50% | 12.919 | 0.532 | 5.722 | 2717.909 |
| FILE | 3 | 80% | 10.813 | 0.610 | 5.800 | 12423.528 |
| FILE | 3 | 100% | 10.158 | 0.667 | 8.675 | 14327.742 |
| IN_MEMORY | 1 | 50% | 0.392 | 0.329 | 0.146 | 0.507 |
| IN_MEMORY | 1 | 80% | 0.380 | 0.358 | 0.192 | 0.503 |
| IN_MEMORY | 1 | 100% | 0.425 | 0.404 | 0.219 | 0.571 |
| IN_MEMORY | 2 | 50% | 0.396 | 0.326 | 0.138 | 0.496 |
| IN_MEMORY | 2 | 80% | 0.383 | 0.361 | 0.199 | 0.527 |
| IN_MEMORY | 2 | 100% | 0.430 | 0.411 | 0.222 | 0.623 |
| IN_MEMORY | 3 | 50% | 0.396 | 0.327 | 0.137 | 0.505 |
| IN_MEMORY | 3 | 80% | 0.391 | 0.359 | 0.201 | 0.546 |
| IN_MEMORY | 3 | 100% | 0.429 | 0.411 | 0.224 | 0.595 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 23898 | 37390.734077 | 5.521407 | 150.286505 |
| FILE | 1 | 2 | 26757 | 68861.495265 | 5.947391 | 467.293416 |
| FILE | 1 | 3 | 29583 | 67227.758793 | 5.746687 | 160.396326 |
| FILE | 2 | 1 | 24201 | 28452.838819 | 4.317183 | 145.124259 |
| FILE | 2 | 2 | 27950 | 67614.117637 | 5.767167 | 388.170421 |
| FILE | 2 | 3 | 29294 | 67562.120028 | 5.664767 | 205.451217 |
| FILE | 3 | 1 | 23224 | 53551.861514 | 5.840895 | 871.303333 |
| FILE | 3 | 2 | 23644 | 66780.600636 | 5.771263 | 410.152867 |
| FILE | 3 | 3 | 29252 | 67516.773285 | 5.844991 | 301.111859 |
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
Source revision `33190572a4385e9df0932cb4cdfcc16db61bc26e`. [CI redline report and captures](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41127515368)

Resource-enforced redline diagnostics (unqualified)

Source revision `33190572a4385e9df0932cb4cdfcc16db61bc26e`; enforcement: systemd-cgroup-v2.

Host: AMD EPYC 7763 64-Core Processor; filesystem/media: /dev/sda1 ext4 (rotational). Allocation: 4 CPU cores, 14733 MiB process-tree memory; 8378 MiB worker heap, 1396 MiB direct memory; free-disk reserve 17.03 GiB.

Execution exit code: 0.

**Redline diagnostics: find a runtime limit (unqualified)**

**Unique writes/s** counts records appended once. This is a fanout workload: every consumer reads the same partition, so one write creates one delivery per consumer. **Deliveries needed/s = unique writes/s × consumers.** **Delivery coverage** is completed deliveries divided by (written records × consumers) within the same window.

For example, 100,000 writes/s with 8 consumers needs 800,000 deliveries/s. 400,000 completed deliveries/s covers 50% of that demand.

Coverage describes aggregate progress during the measurement window, not a record-loss rate or proof that every consumer caught up. It can slightly exceed 100% when operations cross a window boundary; n/a means no writes completed. A completed load step is not a health or capacity pass. Peak production is a short-window observation, not sustained capacity.

One fresh JVM per mode; record bytes 1024, 4096-record batches, 2s warmup and 10s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

| Mode | Highest unique writes/s | Consumers at that rate | Delivery coverage at that rate | Why the test stopped |
| --- | ---: | ---: | ---: | --- |
| FILE | 388,614 | 2 | 96.8% | JVM heap exhausted (JVM_OOM) |

**FILE: delivery progress**

The last measured stage (11, 1024 consumers) covered **0.0%** of its delivery demand. The test stopped at stage 11 with 1024 producers and 1024 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 342,331 | 342,331 | 341,922 | 99.9% |
| 2 | 2 | 388,614 | 777,228 | 752,301 | 96.8% |
| 3 | 4 | 381,553 | 1,526,212 | 749,216 | 49.1% |
| 4 | 8 | 364,172 | 2,913,376 | 677,311 | 23.2% |
| 5 | 16 | 374,459 | 5,991,344 | 619,062 | 10.3% |
| 6 | 32 | 375,991 | 12,031,712 | 749,123 | 6.2% |
| 7 | 64 | 378,579 | 24,229,056 | 724,521 | 3.0% |
| 8 | 128 | 347,472 | 44,476,416 | 632,953 | 1.4% |
| 9 | 256 | 305,751 | 78,272,256 | 674,033 | 0.9% |
| 10 | 512 | 238,433 | 122,077,696 | 251,922 | 0.2% |
| 11 | 1024 | 93,455 | 95,697,920 | 0 | 0.0% |

**FILE: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 10.03 | 55.0% | 6764.0 | 33.554 | Measured |
| 2 | 10.02 | 59.0% | 7550.0 | 67.109 | Measured |
| 3 | 10.03 | 55.3% | 6144.0 | 67.109 | Measured |
| 4 | 10.03 | 53.0% | 3312.0 | 268.435 | Measured |
| 5 | 10.03 | 51.0% | 750.0 | 268.435 | Measured |
| 6 | 10.03 | 51.3% | 2648.0 | 536.871 | Measured |
| 7 | 10.04 | 51.4% | 2488.0 | 1073.742 | Measured |
| 8 | 10.04 | 57.7% | 3286.0 | 2147.484 | Measured |
| 9 | 10.09 | 65.7% | 6298.0 | 4294.967 | Measured |
| 10 | 10.32 | 61.4% | 8072.0 | 8589.935 | Measured |
| 11 | 1.01 | 98.0% | 6744.0 | 536.871 | Partial CPU hold |

FILE: JDK 26.0.2.1; Linux; filesystem ext4; CPU percentages use 4 cores as 100%; 8378 MiB maximum heap.
Admission policy: **unbounded**. Retained-log and FILE write-queue admission are disabled; request/response credits use Long.MAX_VALUE. JVM/OS bounds, protocol constraints, the disk reserve and outer watchdog still apply. Fetch payload: 4 MiB.


Resource-enforced redline diagnostics (unqualified)

Source revision `33190572a4385e9df0932cb4cdfcc16db61bc26e`; enforcement: systemd-cgroup-v2.

Host: AMD EPYC 9V74 80-Core Processor; filesystem/media: /dev/nvme0n1p1 ext4 (non-rotational). Allocation: 4 CPU cores, 14577 MiB process-tree memory; 8284 MiB worker heap, 1380 MiB direct memory; free-disk reserve 17.03 GiB.

Execution exit code: 0.

**Redline diagnostics: find a runtime limit (unqualified)**

**Unique writes/s** counts records appended once. This is a fanout workload: every consumer reads the same partition, so one write creates one delivery per consumer. **Deliveries needed/s = unique writes/s × consumers.** **Delivery coverage** is completed deliveries divided by (written records × consumers) within the same window.

For example, 100,000 writes/s with 8 consumers needs 800,000 deliveries/s. 400,000 completed deliveries/s covers 50% of that demand.

Coverage describes aggregate progress during the measurement window, not a record-loss rate or proof that every consumer caught up. It can slightly exceed 100% when operations cross a window boundary; n/a means no writes completed. A completed load step is not a health or capacity pass. Peak production is a short-window observation, not sustained capacity.

One fresh JVM per mode; record bytes 1024, 4096-record batches, 2s warmup and 10s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

| Mode | Highest unique writes/s | Consumers at that rate | Delivery coverage at that rate | Why the test stopped |
| --- | ---: | ---: | ---: | --- |
| IN_MEMORY | 1,521,791 | 1 | 73.7% | JVM heap exhausted (JVM_OOM) |

**IN_MEMORY: delivery progress**

The last measured stage (3, 4 consumers) covered **16.4%** of its delivery demand. The test stopped at stage 4 with 8 producers and 8 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 1,521,791 | 1,521,791 | 1,121,448 | 73.7% |
| 2 | 2 | 778,091 | 1,556,182 | 562,311 | 36.1% |
| 3 | 4 | 639,580 | 2,558,320 | 418,375 | 16.4% |

**IN_MEMORY: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 10.04 | 83.2% | 7238.0 | 16.777 | Measured |
| 2 | 10.21 | 59.0% | 8072.0 | 268.435 | Measured |
| 3 | 10.05 | 52.3% | 8284.0 | 536.871 | Measured |

IN_MEMORY: JDK 26.0.2.1; Linux; filesystem ext4; CPU percentages use 4 cores as 100%; 8284 MiB maximum heap.
Admission policy: **unbounded**. Retained-log and FILE write-queue admission are disabled; request/response credits use Long.MAX_VALUE. JVM/OS bounds, protocol constraints, the disk reserve and outer watchdog still apply. Fetch payload: 4 MiB.

<!-- benchmark-redline:end -->

### Contributor PC benchmarks

<!-- benchmark-contributor-pc:start -->
Latest contributor PC run (diagnostic, unqualified)

[![memory writes (contributor PC): 500.00K/s target, 499.97K/s actual, 100%, unqualified](benchmark-badges/contributor-memory-throughput.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41127515368)
[![memory p99 (contributor PC): 2.15 ms at target](benchmark-badges/contributor-memory-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41127515368)
[![memory resources (contributor PC): 15.4% CPU, 510 MiB heap](benchmark-badges/contributor-memory-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41127515368)
[![memory JFR (contributor PC): 1.66 GiB/s alloc, 1.0 ms GC](benchmark-badges/contributor-memory-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41127515368)
[![file writes (contributor PC): 250.00K/s target, 58.51K/s actual, 23%, unqualified](benchmark-badges/contributor-file-throughput.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41127515368)
[![file p99 (contributor PC): 7587.50 ms at target](benchmark-badges/contributor-file-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41127515368)
[![file resources (contributor PC): 30.8% CPU, 510 MiB heap](benchmark-badges/contributor-file-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41127515368)
[![file JFR (contributor PC): 0.27 GiB/s alloc, 0.3 ms GC](benchmark-badges/contributor-file-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41127515368)

[Report, logs and downloadable results](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41127515368)

Contributor: Rishabh Rathi; finished 2026-10-30T20:54:00.375971+00:00; revision `9a0bba7fa852d032321ade7b3dd3954b509aa264`.

Windows-11-10.0.26100-SP0; AMD Ryzen 5 7600X 6-Core Processor; 8 JVM active processors; 512 MiB heap; 128 MiB direct-memory limit; no OS resource quota.

JDK: openjdk version "26.0.2.1" 2026-08-18 OpenJDK Runtime Environment Temurin-26.0.2.1+1 (build 26.0.2.1+1) OpenJDK 64-Bit Server VM Temurin-26.0.2.1+1 (build 26.0.2.1+1, mixed mode, sharing). Media: Filesystem per worker; physical media not detected.

Two fresh JVMs per mode; 1 KiB records; 256-record batches; 5s minimum adaptive warmup and 10s measurement at each load. Broker and clients share the PC. These results are contributor supplied and do not qualify sustained capacity.

**Contributor PC summary (diagnostic, unqualified)**

CI-style summary: median actual writes/s across the two full-target windows; worst p99 at that target. Heap, average CPU, allocation rate and GC pause values are maxima across all fixed-target windows. Window checks may pass while these contributor measurements remain unqualified.

| Mode | Reference writes/s | Median actual writes/s | Write target achieved | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Window checks | Status |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| IN_MEMORY | 500,000 | 499,968 | 100.0% | 2.150 | 510.0 | 15.4448 | Missed | Unqualified |
| FILE | 250,000 | 58,509 | 23.4% | 7587.496 | 510.0 | 30.7765 | Missed | Unqualified |

**Fixed target: requested writes versus completed work**

These windows count unique records, with one delivery per record. Requested writes/s is the offered load; actual writes/s is the completed rate. Write target achieved compares actual with requested writes. Delivery coverage compares unique deliveries with completed writes. Consumers keeping up does not mean the write target was met.

| Mode | Repeat | Requested writes/s | Actual writes/s | Unique deliveries/s | Write target achieved | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| IN_MEMORY | 1 | 250,000 | 249,984 | 250,010 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 400,000 | 399,974 | 399,974 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 500,000 | 499,994 | 500,019 | 100.0% | 100.0% |
| FILE | 1 | 125,000 | 52,915 | 52,966 | 42.3% | 100.1% |
| FILE | 1 | 200,000 | 60,339 | 60,390 | 30.2% | 100.1% |
| FILE | 1 | 250,000 | 58,906 | 58,931 | 23.6% | 100.0% |
| IN_MEMORY | 2 | 250,000 | 249,984 | 250,010 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 400,000 | 400,000 | 400,000 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 500,000 | 499,942 | 499,968 | 100.0% | 100.0% |
| FILE | 2 | 125,000 | 56,474 | 56,525 | 45.2% | 100.1% |
| FILE | 2 | 200,000 | 57,344 | 57,395 | 28.7% | 100.1% |
| FILE | 2 | 250,000 | 58,112 | 58,163 | 23.2% | 100.1% |

**Fixed target: latency and resource checks**

Window checks include throughput, consumption, latency, CPU and heap limits. A passed window remains a local diagnostic, not qualified sustained capacity.

| Mode | Repeat | Load | p99 ms | CPU budget used | Force p99 ms | Window checks |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 1 | 50% | 2.343 | 8.9% | 0.000 | Missed |
| IN_MEMORY | 1 | 80% | 2.161 | 11.9% | 0.000 | Missed |
| IN_MEMORY | 1 | 100% | 2.118 | 15.4% | 0.000 | Passed |
| FILE | 1 | 50% | 5628.756 | 30.6% | 7.602 | Missed |
| FILE | 1 | 80% | 6912.213 | 29.7% | 5.882 | Missed |
| FILE | 1 | 100% | 7570.719 | 29.5% | 6.234 | Missed |
| IN_MEMORY | 2 | 50% | 2.300 | 9.8% | 0.000 | Missed |
| IN_MEMORY | 2 | 80% | 2.187 | 12.6% | 0.000 | Passed |
| IN_MEMORY | 2 | 100% | 2.150 | 15.0% | 0.000 | Passed |
| FILE | 2 | 50% | 5427.429 | 30.7% | 7.242 | Missed |
| FILE | 2 | 80% | 7046.431 | 30.8% | 6.431 | Missed |
| FILE | 2 | 100% | 7587.496 | 30.2% | 6.373 | Missed |

Collected for revision `33190572a4385e9df0932cb4cdfcc16db61bc26e`. Only generated benchmark README blocks and JSON/SVG snapshots differ from the measured source above; code, configuration, tools and manual documentation match.

**Redline diagnostics: find a runtime limit (unqualified)**

**Unique writes/s** counts records appended once. This is a fanout workload: every consumer reads the same partition, so one write creates one delivery per consumer. **Deliveries needed/s = unique writes/s × consumers.** **Delivery coverage** is completed deliveries divided by (written records × consumers) within the same window.

For example, 100,000 writes/s with 8 consumers needs 800,000 deliveries/s. 400,000 completed deliveries/s covers 50% of that demand.

Coverage describes aggregate progress during the measurement window, not a record-loss rate or proof that every consumer caught up. It can slightly exceed 100% when operations cross a window boundary; n/a means no writes completed. A completed load step is not a health or capacity pass. Peak production is a short-window observation, not sustained capacity.

One fresh JVM per mode; record bytes 1024, 4096-record batches, 2s warmup and 10s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

| Mode | Highest unique writes/s | Consumers at that rate | Delivery coverage at that rate | Why the test stopped |
| --- | ---: | ---: | ---: | --- |
| IN_MEMORY | 1,388,293 | 1 | 100.0% | JVM heap exhausted (JVM_OOM) |
| FILE | 427,891 | 128 | 1.5% | Broker/client limit reached (BROKER_OR_CLIENT_LIMIT): INTERNAL_ERROR |

**IN_MEMORY: delivery progress**

The last measured stage (2, 2 consumers) covered **44.5%** of its delivery demand. The test stopped at stage 3 with 4 producers and 4 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 1,388,293 | 1,388,293 | 1,388,293 | 100.0% |
| 2 | 2 | 1,064,414 | 2,128,828 | 947,100 | 44.5% |

**IN_MEMORY: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 10.04 | 18.5% | 1900.0 | 16.777 | Measured |
| 2 | 10.02 | 42.8% | 9050.0 | 134.218 | Measured |

IN_MEMORY: JDK 26.0.2.1; Windows_11; filesystem NTFS; CPU percentages use 12 cores as 100%; 9050 MiB maximum heap.
Free-disk reserve: 115.39 GiB (sampled).
Admission policy: **unbounded**. Retained-log and FILE write-queue admission are disabled; request/response credits use Long.MAX_VALUE. JVM/OS bounds, protocol constraints, the disk reserve and outer watchdog still apply. Fetch payload: 4 MiB.

**FILE: delivery progress**

The last measured stage (9, 256 consumers) covered **1.2%** of its delivery demand. The test stopped at stage 9 with 256 producers and 256 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 185,080 | 185,080 | 185,080 | 100.0% |
| 2 | 2 | 159,823 | 319,646 | 319,238 | 99.9% |
| 3 | 4 | 183,538 | 734,152 | 296,924 | 40.4% |
| 4 | 8 | 237,375 | 1,899,000 | 313,514 | 16.5% |
| 5 | 16 | 336,171 | 5,378,736 | 358,174 | 6.7% |
| 6 | 32 | 342,517 | 10,960,544 | 397,906 | 3.6% |
| 7 | 64 | 398,992 | 25,535,488 | 546,736 | 2.1% |
| 8 | 128 | 427,891 | 54,770,048 | 834,631 | 1.5% |
| 9 | 256 | 372,600 | 95,385,600 | 1,174,154 | 1.2% |

**FILE: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 10.05 | 12.3% | 1226.0 | 67.109 | Measured |
| 2 | 10.05 | 14.4% | 336.0 | 134.218 | Measured |
| 3 | 10.04 | 13.8% | 1646.0 | 268.435 | Measured |
| 4 | 10.06 | 14.8% | 510.0 | 536.871 | Measured |
| 5 | 10.05 | 18.1% | 3336.0 | 1073.742 | Measured |
| 6 | 10.06 | 20.5% | 458.0 | 1073.742 | Measured |
| 7 | 10.09 | 25.8% | 5436.0 | 2147.484 | Measured |
| 8 | 10.07 | 36.4% | 3904.0 | 4294.967 | Measured |
| 9 | 6.18 | 52.3% | 6940.0 | 2147.484 | Broker/client limit reached |

FILE: JDK 26.0.2.1; Windows_11; filesystem NTFS; CPU percentages use 12 cores as 100%; 9050 MiB maximum heap.
Free-disk reserve: 115.39 GiB (sampled).
Admission policy: **unbounded**. Retained-log and FILE write-queue admission are disabled; request/response credits use Long.MAX_VALUE. JVM/OS bounds, protocol constraints, the disk reserve and outer watchdog still apply. Fetch payload: 4 MiB.
<!-- benchmark-contributor-pc:end -->

The [mandatory contributor pre-push check](scripts/README.md#contributor-pc-check-before-every-push) attaches local performance evidence. CI validates the attachment and retains it with the pipeline artifacts; default-branch publication refreshes the block above independently of the CI measurements.

## Help and contributions

For setup help or a reproducible bug, start with [support and troubleshooting](SUPPORT.md). Use the [issue forms](https://github.com/RishabhRathi-Dev/Setu/issues/new/choose) for bugs and feature requests. Report vulnerabilities privately following the [security policy](SECURITY.md).

Contributions should include focused changes, relevant verification, and updated documentation. The [contributing guide](CONTRIBUTING.md) covers setup, checks, and pull requests; [AGENTS.md](AGENTS.md) records the repository map and contracts for coding agents.
