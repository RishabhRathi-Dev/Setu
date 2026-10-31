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
[![in-memory: 500.00K/s target, 457.05K/s CI, 91% unqualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41180557125)
[![file: 250.00K/s target, 249.99K/s CI, 100% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41180557125)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest CI target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 3537.90 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41180557125)
[![memory resources: 78% CPU, 254 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41180557125)
[![memory JFR: 1.52 GiB/s alloc, 75.5 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41180557125)
[![file p99: 13430.16 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41180557125)
[![file resources: 61% CPU, 124 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41180557125)
[![file JFR: 1.12 GiB/s alloc, 25.7 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41180557125)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `946c90b82348599af4732f622e2ded33214e876a`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41180557125).

Runner: AMD EPYC 9V74 80-Core Processor; 4.0 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

Runner: AMD EPYC 9V74 80-Core Processor; 4.0 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

Runner: AMD EPYC 7763 64-Core Processor; 4.0 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

Runner: AMD EPYC 9V45 96-Core Processor; 4.0 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

Runner: AMD EPYC 7763 64-Core Processor; 4.0 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

Runner: AMD EPYC 7763 64-Core Processor; 4.0 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

# Node-target measurements

Each load stage uses a fresh JVM; warmup and measurement share that JVM.

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

Writes count unique records; each record needs one delivery in this workload. Write target achieved compares actual writes with requested writes. Delivery coverage compares unique deliveries with completed writes in the same window. Similar write and delivery rates do not guarantee the requested load or other limits were met.

| Mode | Reference writes/s | Median actual writes/s | Write target achieved | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 457,045 | 91.4% | 3537.895 | 254 | 78.1985 | False |
| FILE | 250,000 | 249,993 | 100.0% | 13430.161 | 124 | 60.6968 | False |

**Fixed-target traffic**

| Mode | Repeat | Requested writes/s | Actual writes/s | Unique deliveries/s | Write target achieved | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 125,000 | 124,996 | 125,030 | 100.0% | 100.0% |
| FILE | 1 | 200,000 | 199,987 | 200,030 | 100.0% | 100.0% |
| FILE | 1 | 250,000 | 249,993 | 260,975 | 100.0% | 104.4% |
| FILE | 2 | 125,000 | 105,617 | 105,634 | 84.5% | 100.0% |
| FILE | 2 | 200,000 | 131,302 | 131,302 | 65.7% | 100.0% |
| FILE | 2 | 250,000 | 133,495 | 133,495 | 53.4% | 100.0% |
| FILE | 3 | 125,000 | 124,996 | 125,013 | 100.0% | 100.0% |
| FILE | 3 | 200,000 | 199,987 | 200,004 | 100.0% | 100.0% |
| FILE | 3 | 250,000 | 249,993 | 265,481 | 100.0% | 106.2% |
| IN_MEMORY | 1 | 250,000 | 249,993 | 250,001 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 400,000 | 400,000 | 400,060 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 500,000 | 499,994 | 500,045 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 250,000 | 249,993 | 250,027 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 400,000 | 400,000 | 401,015 | 100.0% | 100.3% |
| IN_MEMORY | 2 | 500,000 | 457,045 | 457,054 | 91.4% | 100.0% |
| IN_MEMORY | 3 | 250,000 | 249,993 | 250,035 | 100.0% | 100.0% |
| IN_MEMORY | 3 | 400,000 | 400,000 | 401,033 | 100.0% | 100.3% |
| IN_MEMORY | 3 | 500,000 | 440,525 | 440,533 | 88.1% | 100.0% |

**Fixed-target latency and resource checks**

| Mode | Repeat | Load | p99 ms | CPU % | Heap slope MiB/s | Qualified window |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 2.732 | 47.1666 | 0.090 | True |
| FILE | 1 | 80% | 2.542 | 54.8227 | 0.188 | True |
| FILE | 1 | 100% | 6.087 | 60.3816 | 0.200 | True |
| FILE | 2 | 50% | 4928.307 | 24.9076 | 0.375 | False |
| FILE | 2 | 80% | 10267.656 | 27.0994 | 0.108 | False |
| FILE | 2 | 100% | 13430.161 | 26.5910 | 0.011 | False |
| FILE | 3 | 50% | 3.453 | 50.8808 | -0.008 | True |
| FILE | 3 | 80% | 4.448 | 57.4060 | 0.052 | True |
| FILE | 3 | 100% | 6.042 | 60.6968 | 0.137 | True |
| IN_MEMORY | 1 | 50% | 0.486 | 27.7325 | -0.028 | True |
| IN_MEMORY | 1 | 80% | 0.364 | 35.0075 | -0.014 | True |
| IN_MEMORY | 1 | 100% | 0.465 | 40.2571 | 0.373 | True |
| IN_MEMORY | 2 | 50% | 0.551 | 42.2736 | -0.006 | True |
| IN_MEMORY | 2 | 80% | 11.796 | 53.6232 | -0.406 | True |
| IN_MEMORY | 2 | 100% | 2560.623 | 77.4875 | 0.008 | False |
| IN_MEMORY | 3 | 50% | 0.599 | 42.8731 | -0.172 | True |
| IN_MEMORY | 3 | 80% | 40.796 | 54.9819 | 0.166 | True |
| IN_MEMORY | 3 | 100% | 3537.895 | 78.1985 | 0.055 | False |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 2 | 50% | throughput 105,617/s <95% of offered 125,000/s; p99 4928.307 ms outside [0,10) ms |
| FILE | 2 | 80% | throughput 131,302/s <95% of offered 200,000/s; p99 10267.656 ms outside [0,100) ms |
| FILE | 2 | 100% | throughput 133,495/s <95% of offered 250,000/s; p99 13430.161 ms outside [0,100) ms |
| IN_MEMORY | 2 | 100% | throughput 457,045/s <95% of offered 500,000/s; p99 2560.623 ms outside [0,50) ms |
| IN_MEMORY | 3 | 100% | throughput 440,525/s <95% of offered 500,000/s; p99 3537.895 ms outside [0,50) ms |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 2.363 | 0.550 | 1.616 | 2.732 |
| FILE | 1 | 80% | 2.187 | 0.653 | 1.718 | 2.542 |
| FILE | 1 | 100% | 2.734 | 0.938 | 2.286 | 6.087 |
| FILE | 2 | 50% | 56.132 | 0.556 | 23.167 | 4928.307 |
| FILE | 2 | 80% | 25.346 | 0.617 | 18.727 | 10267.656 |
| FILE | 2 | 100% | 16.499 | 0.635 | 11.223 | 13430.161 |
| FILE | 3 | 50% | 3.131 | 0.616 | 2.241 | 3.453 |
| FILE | 3 | 80% | 3.254 | 0.780 | 2.570 | 4.448 |
| FILE | 3 | 100% | 3.555 | 1.154 | 2.980 | 6.042 |
| IN_MEMORY | 1 | 50% | 0.322 | 0.166 | 0.175 | 0.486 |
| IN_MEMORY | 1 | 80% | 0.255 | 0.192 | 0.151 | 0.364 |
| IN_MEMORY | 1 | 100% | 0.291 | 0.228 | 0.168 | 0.465 |
| IN_MEMORY | 2 | 50% | 0.418 | 0.317 | 0.179 | 0.551 |
| IN_MEMORY | 2 | 80% | 0.585 | 0.428 | 0.316 | 11.796 |
| IN_MEMORY | 2 | 100% | 2.130 | 1.043 | 0.810 | 2560.623 |
| IN_MEMORY | 3 | 50% | 0.446 | 0.331 | 0.189 | 0.599 |
| IN_MEMORY | 3 | 80% | 0.908 | 0.481 | 0.347 | 40.796 |
| IN_MEMORY | 3 | 100% | 2.175 | 1.096 | 0.832 | 3537.895 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 23195 | 10591.125281 | 1.126399 | 38.524396 |
| FILE | 1 | 2 | 37292 | 16737.425930 | 1.020927 | 2.859817 |
| FILE | 1 | 3 | 49121 | 23533.230394 | 1.218559 | 18.785309 |
| FILE | 2 | 1 | 21468 | 68458.943887 | 34.340863 | 237.045167 |
| FILE | 2 | 2 | 26517 | 67360.181354 | 21.921791 | 148.691897 |
| FILE | 2 | 3 | 27218 | 67512.369779 | 12.279807 | 185.173238 |
| FILE | 3 | 1 | 23939 | 14445.981354 | 1.989631 | 20.017280 |
| FILE | 3 | 2 | 38827 | 23899.974472 | 2.034687 | 5.853245 |
| FILE | 3 | 3 | 53438 | 33125.776654 | 2.250751 | 6.207457 |
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

[![memory writes (contributor PC): 500.00K/s target, 499.99K/s actual, 100%, unqualified](benchmark-badges/contributor-memory-throughput.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41180557125)
[![memory p99 (contributor PC): 2.04 ms at target](benchmark-badges/contributor-memory-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41180557125)
[![memory resources (contributor PC): 15.1% CPU, 504 MiB heap](benchmark-badges/contributor-memory-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41180557125)
[![memory JFR (contributor PC): 1.68 GiB/s alloc, 1.0 ms GC](benchmark-badges/contributor-memory-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41180557125)
[![file writes (contributor PC): 250.00K/s target, 56.76K/s actual, 23%, unqualified](benchmark-badges/contributor-file-throughput.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41180557125)
[![file p99 (contributor PC): 7658.80 ms at target](benchmark-badges/contributor-file-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41180557125)
[![file resources (contributor PC): 31.5% CPU, 482 MiB heap](benchmark-badges/contributor-file-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41180557125)
[![file JFR (contributor PC): 0.26 GiB/s alloc, 0.4 ms GC](benchmark-badges/contributor-file-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41180557125)

[Report, logs and downloadable results](https://github.com/RishabhRathi-Dev/Setu/actions/runs/41180557125)

Contributor: Rishabh Rathi; finished 2026-10-31T07:18:15.446872+00:00; revision `e525bc6440e96abc3eed445712939aaf2481352d`.

Windows-11-10.0.26100-SP0; AMD Ryzen 5 7600X 6-Core Processor; 8 JVM active processors; 512 MiB heap; 128 MiB direct-memory limit; no OS resource quota.

JDK: openjdk version "26.0.2.1" 2026-08-18 OpenJDK Runtime Environment Temurin-26.0.2.1+1 (build 26.0.2.1+1) OpenJDK 64-Bit Server VM Temurin-26.0.2.1+1 (build 26.0.2.1+1, mixed mode, sharing). Media: Filesystem per worker; physical media not detected.

Two fresh JVMs per mode; 1 KiB records; 256-record batches; 5s minimum adaptive warmup and 10s measurement at each load. Broker and clients share the PC. These results are contributor supplied and do not qualify sustained capacity.

**Contributor PC summary (diagnostic, unqualified)**

CI-style summary: median actual writes/s across the two full-target windows; worst p99 at that target. Heap, average CPU, allocation rate and GC pause values are maxima across all fixed-target windows. Window checks may pass while these contributor measurements remain unqualified.

| Mode | Reference writes/s | Median actual writes/s | Write target achieved | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Window checks | Status |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| IN_MEMORY | 500,000 | 499,994 | 100.0% | 2.040 | 504.0 | 15.0976 | Missed | Unqualified |
| FILE | 250,000 | 56,755 | 22.7% | 7658.799 | 482.0 | 31.5429 | Missed | Unqualified |

**Fixed target: requested writes versus completed work**

These windows count unique records, with one delivery per record. Requested writes/s is the offered load; actual writes/s is the completed rate. Write target achieved compares actual with requested writes. Delivery coverage compares unique deliveries with completed writes. Consumers keeping up does not mean the write target was met.

| Mode | Repeat | Requested writes/s | Actual writes/s | Unique deliveries/s | Write target achieved | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| IN_MEMORY | 1 | 250,000 | 249,984 | 249,984 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 400,000 | 400,000 | 400,026 | 100.0% | 100.0% |
| IN_MEMORY | 1 | 500,000 | 499,994 | 499,994 | 100.0% | 100.0% |
| FILE | 1 | 125,000 | 57,190 | 57,242 | 45.8% | 100.1% |
| FILE | 1 | 200,000 | 57,190 | 57,242 | 28.6% | 100.1% |
| FILE | 1 | 250,000 | 56,960 | 56,986 | 22.8% | 100.0% |
| IN_MEMORY | 2 | 250,000 | 249,958 | 249,958 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 400,000 | 400,000 | 400,000 | 100.0% | 100.0% |
| IN_MEMORY | 2 | 500,000 | 499,994 | 500,019 | 100.0% | 100.0% |
| FILE | 2 | 125,000 | 57,114 | 57,165 | 45.7% | 100.1% |
| FILE | 2 | 200,000 | 58,061 | 58,112 | 29.0% | 100.1% |
| FILE | 2 | 250,000 | 56,550 | 56,602 | 22.6% | 100.1% |

**Fixed target: latency and resource checks**

Window checks include throughput, consumption, latency, CPU and heap limits. A passed window remains a local diagnostic, not qualified sustained capacity.

| Mode | Repeat | Load | p99 ms | CPU budget used | Force p99 ms | Window checks |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 1 | 50% | 2.071 | 8.9% | 0.000 | Missed |
| IN_MEMORY | 1 | 80% | 2.060 | 12.8% | 0.000 | Missed |
| IN_MEMORY | 1 | 100% | 2.035 | 14.7% | 0.000 | Passed |
| FILE | 1 | 50% | 5347.738 | 30.6% | 6.300 | Missed |
| FILE | 1 | 80% | 7059.014 | 31.5% | 5.927 | Missed |
| FILE | 1 | 100% | 7637.828 | 30.8% | 5.898 | Missed |
| IN_MEMORY | 2 | 50% | 2.067 | 9.6% | 0.000 | Missed |
| IN_MEMORY | 2 | 80% | 2.073 | 12.6% | 0.000 | Passed |
| IN_MEMORY | 2 | 100% | 2.040 | 15.1% | 0.000 | Missed |
| FILE | 2 | 50% | 5385.486 | 31.1% | 6.005 | Missed |
| FILE | 2 | 80% | 7008.682 | 31.5% | 6.042 | Missed |
| FILE | 2 | 100% | 7658.799 | 31.4% | 6.328 | Missed |

**Redline diagnostics: find a runtime limit (unqualified)**

**Unique writes/s** counts records appended once. This is a fanout workload: every consumer reads the same partition, so one write creates one delivery per consumer. **Deliveries needed/s = unique writes/s × consumers.** **Delivery coverage** is completed deliveries divided by (written records × consumers) within the same window.

For example, 100,000 writes/s with 8 consumers needs 800,000 deliveries/s. 400,000 completed deliveries/s covers 50% of that demand.

Coverage describes aggregate progress during the measurement window, not a record-loss rate or proof that every consumer caught up. It can slightly exceed 100% when operations cross a window boundary; n/a means no writes completed. A completed load step is not a health or capacity pass. Peak production is a short-window observation, not sustained capacity.

One fresh JVM per stage; warmup and measurement share that JVM. Heap, connections and broker state start fresh at each concurrency level. Record bytes 1024, 4096-record batches, 2s warmup and 10s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

| Mode | Highest unique writes/s | Consumers at that rate | Delivery coverage at that rate | Why the test stopped |
| --- | ---: | ---: | ---: | --- |
| IN_MEMORY | 1,362,681 | 1 | 100.0% | JVM heap exhausted (JVM_OOM) |
| FILE | 488,498 | 512 | 0.2% | JVM heap exhausted (JVM_OOM) |

**IN_MEMORY: delivery progress**

The last measured stage (2, 2 consumers) covered **43.0%** of its delivery demand. The test stopped at stage 3 with 4 producers and 4 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 1,362,681 | 1,362,681 | 1,363,088 | 100.0% |
| 2 | 2 | 744,362 | 1,488,724 | 639,517 | 43.0% |

**IN_MEMORY: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 10.05 | 18.3% | 7888.0 | 16.777 | Measured |
| 2 | 10.98 | 41.7% | 10260.0 | 268.435 | Measured |

IN_MEMORY: JDK 26.0.2.1; Windows_11; filesystem NTFS; CPU percentages use 12 cores as 100%; 10260 MiB maximum heap.
Free-disk reserve: 115.35 GiB (sampled).
Admission policy: **unbounded**. Retained-log and FILE write-queue admission are disabled; request/response credits use Long.MAX_VALUE. JVM/OS bounds, protocol constraints, the disk reserve and outer watchdog still apply. Fetch payload: 4 MiB.

**FILE: delivery progress**

The last measured stage (10, 512 consumers) covered **0.2%** of its delivery demand. The test stopped at stage 11 with 1024 producers and 1024 consumers. The failing attempt can occur after the last measured window.

Each row uses equal numbers of producers and consumers. Rates are records or deliveries per second.

| Stage | Consumers | Unique writes/s | Deliveries needed/s | Completed deliveries/s | Delivery coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 145,009 | 145,009 | 145,009 | 100.0% |
| 2 | 2 | 140,882 | 281,764 | 298,457 | 105.9% |
| 3 | 4 | 189,846 | 759,384 | 281,103 | 37.0% |
| 4 | 8 | 216,794 | 1,734,352 | 233,877 | 13.5% |
| 5 | 16 | 274,159 | 4,386,544 | 218,432 | 5.0% |
| 6 | 32 | 325,700 | 10,422,400 | 234,797 | 2.3% |
| 7 | 64 | 444,338 | 28,437,632 | 502,171 | 1.8% |
| 8 | 128 | 437,339 | 55,979,392 | 728,492 | 1.3% |
| 9 | 256 | 441,232 | 112,955,392 | 1,189,000 | 1.1% |
| 10 | 512 | 488,498 | 250,110,976 | 603,604 | 0.2% |

**FILE: timing and resources**

Measured means the load step finished. Partial CPU hold means only its prefix survived. p99 is a coarse request-cycle estimate; heap is the reported stage sample.

| Stage | Measured seconds | CPU budget used | Reported heap MiB | Coarse p99 ms | Window status |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 10.06 | 11.8% | 1652.0 | 67.109 | Measured |
| 2 | 10.06 | 16.1% | 1116.0 | 134.218 | Measured |
| 3 | 10.05 | 14.7% | 1832.0 | 268.435 | Measured |
| 4 | 10.07 | 16.2% | 7416.0 | 536.871 | Measured |
| 5 | 10.07 | 18.1% | 3850.0 | 1073.742 | Measured |
| 6 | 10.05 | 17.1% | 7402.0 | 1073.742 | Measured |
| 7 | 10.06 | 27.3% | 2192.0 | 2147.484 | Measured |
| 8 | 10.09 | 35.8% | 1810.0 | 4294.967 | Measured |
| 9 | 10.04 | 53.6% | 5474.0 | 4294.967 | Measured |
| 10 | 10.21 | 45.7% | 10172.0 | 8589.935 | Measured |

FILE: JDK 26.0.2.1; Windows_11; filesystem NTFS; CPU percentages use 12 cores as 100%; 10260 MiB maximum heap.
Free-disk reserve: 115.35 GiB (sampled).
Admission policy: **unbounded**. Retained-log and FILE write-queue admission are disabled; request/response credits use Long.MAX_VALUE. JVM/OS bounds, protocol constraints, the disk reserve and outer watchdog still apply. Fetch payload: 4 MiB.
<!-- benchmark-contributor-pc:end -->

The [mandatory contributor pre-push check](scripts/README.md#contributor-pc-check-before-every-push) attaches local performance evidence. CI validates the attachment and retains it with the pipeline artifacts; default-branch publication refreshes the block above independently of the CI measurements.

## Help and contributions

For setup help or a reproducible bug, start with [support and troubleshooting](SUPPORT.md). Use the [issue forms](https://github.com/RishabhRathi-Dev/Setu/issues/new/choose) for bugs and feature requests. Report vulnerabilities privately following the [security policy](SECURITY.md).

Contributions should include focused changes, relevant verification, and updated documentation. The [contributing guide](CONTRIBUTING.md) covers setup, checks, and pull requests; [AGENTS.md](AGENTS.md) records the repository map and contracts for coding agents.
