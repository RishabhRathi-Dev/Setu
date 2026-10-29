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
[![in-memory: 500.00K/s target, 499.99K/s CI, 100% qualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40923401078)
[![file: 250.00K/s target, 249.99K/s CI, 100% qualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40923401078)
<!-- benchmark-metrics-badges:end -->

<details>
<summary>Latest CI target diagnostics and qualification details</summary>

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 0.43 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40923401078)
[![memory resources: 41% CPU, 256 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40923401078)
[![memory JFR: 1.66 GiB/s alloc, 9.9 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40923401078)
[![file p99: 4.60 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40923401078)
[![file resources: 56% CPU, 124 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40923401078)
[![file JFR: 1.09 GiB/s alloc, 20.4 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40923401078)
<!-- benchmark-target-diagnostics:end -->

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `c50491838aa195c37eba13631233c787bae06281`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40923401078).

Runner: AMD EPYC 9V74 80-Core Processor; 4.0 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

Runner: AMD EPYC 9V45 96-Core Processor; 4.0 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.

| Mode | Target/s | Median measured/s | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 499,994 | 0.429 | 256 | 40.7158 | True |
| FILE | 250,000 | 249,993 | 4.596 | 124 | 56.4564 | True |

| Mode | Repeat | Load | Measured/s | Consumed/s | p99 ms | CPU % | Heap slope MiB/s | Healthy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 124,996 | 125,039 | 3.041 | 47.2142 | 0.039 | True |
| FILE | 1 | 80% | 199,987 | 200,004 | 4.854 | 53.6733 | -0.004 | True |
| FILE | 1 | 100% | 249,993 | 250,001 | 3.834 | 56.3565 | -0.081 | True |
| FILE | 2 | 50% | 124,996 | 125,005 | 3.078 | 45.8315 | 0.033 | True |
| FILE | 2 | 80% | 199,987 | 200,004 | 3.799 | 52.7900 | 0.131 | True |
| FILE | 2 | 100% | 249,993 | 250,010 | 3.729 | 56.1567 | 0.019 | True |
| FILE | 3 | 50% | 124,996 | 125,022 | 2.658 | 46.8488 | 0.145 | True |
| FILE | 3 | 80% | 199,987 | 200,013 | 3.095 | 52.5309 | -0.100 | True |
| FILE | 3 | 100% | 249,993 | 250,001 | 4.596 | 56.4564 | -0.059 | True |
| IN_MEMORY | 1 | 50% | 249,993 | 250,001 | 0.517 | 28.6156 | 0.475 | True |
| IN_MEMORY | 1 | 80% | 400,000 | 400,009 | 0.318 | 35.6583 | 0.401 | True |
| IN_MEMORY | 1 | 100% | 499,994 | 500,002 | 0.429 | 40.6908 | 0.692 | True |
| IN_MEMORY | 2 | 50% | 249,993 | 250,001 | 0.669 | 28.3575 | 0.176 | True |
| IN_MEMORY | 2 | 80% | 400,000 | 400,009 | 0.370 | 34.9662 | 0.655 | True |
| IN_MEMORY | 2 | 100% | 499,994 | 500,011 | 0.355 | 39.8157 | 0.317 | True |
| IN_MEMORY | 3 | 50% | 249,993 | 250,001 | 0.680 | 28.3497 | -0.138 | True |
| IN_MEMORY | 3 | 80% | 400,000 | 400,009 | 0.368 | 35.6159 | 0.357 | True |
| IN_MEMORY | 3 | 100% | 499,994 | 500,002 | 0.402 | 40.7158 | 0.372 | True |

Latency components (scheduled completion includes offered-load scheduling delay):

| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 50% | 2.478 | 0.547 | 1.592 | 3.041 |
| FILE | 1 | 80% | 3.498 | 0.811 | 2.847 | 4.854 |
| FILE | 1 | 100% | 2.898 | 0.764 | 2.378 | 3.834 |
| FILE | 2 | 50% | 2.738 | 0.545 | 1.591 | 3.078 |
| FILE | 2 | 80% | 2.777 | 0.653 | 2.093 | 3.799 |
| FILE | 2 | 100% | 2.705 | 0.743 | 2.241 | 3.729 |
| FILE | 3 | 50% | 2.460 | 0.532 | 1.556 | 2.658 |
| FILE | 3 | 80% | 2.554 | 0.623 | 1.784 | 3.095 |
| FILE | 3 | 100% | 2.849 | 0.768 | 2.288 | 4.596 |
| IN_MEMORY | 1 | 50% | 0.367 | 0.186 | 0.212 | 0.517 |
| IN_MEMORY | 1 | 80% | 0.234 | 0.179 | 0.122 | 0.318 |
| IN_MEMORY | 1 | 100% | 0.279 | 0.215 | 0.168 | 0.429 |
| IN_MEMORY | 2 | 50% | 0.460 | 0.232 | 0.283 | 0.669 |
| IN_MEMORY | 2 | 80% | 0.266 | 0.182 | 0.151 | 0.370 |
| IN_MEMORY | 2 | 100% | 0.256 | 0.194 | 0.144 | 0.355 |
| IN_MEMORY | 3 | 50% | 0.402 | 0.193 | 0.221 | 0.680 |
| IN_MEMORY | 3 | 80% | 0.282 | 0.216 | 0.159 | 0.368 |
| IN_MEMORY | 3 | 100% | 0.283 | 0.236 | 0.161 | 0.402 |

File-force waits from measured JFR stages:

| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FILE | 1 | 1 | 23344 | 10708.972491 | 1.112063 | 17.916717 |
| FILE | 1 | 2 | 38231 | 18363.041625 | 1.659903 | 8.503664 |
| FILE | 1 | 3 | 47889 | 22704.027042 | 1.381375 | 5.814757 |
| FILE | 2 | 1 | 23413 | 10545.806149 | 1.224703 | 8.805417 |
| FILE | 2 | 2 | 37522 | 16354.940501 | 1.244159 | 17.980263 |
| FILE | 2 | 3 | 47499 | 21518.280621 | 1.270783 | 10.033965 |
| FILE | 3 | 1 | 23292 | 10014.270905 | 1.095679 | 5.339797 |
| FILE | 3 | 2 | 37288 | 15954.201022 | 1.170431 | 7.963676 |
| FILE | 3 | 3 | 47580 | 20964.887852 | 1.252351 | 13.571991 |
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
Source revision `c50491838aa195c37eba13631233c787bae06281`. [CI redline report and captures](https://github.com/RishabhRathi-Dev/Setu/actions/runs/40923401078)

Resource-enforced redline diagnostics (unqualified)

Source revision `c50491838aa195c37eba13631233c787bae06281`; enforcement: systemd-cgroup-v2.

Host: AMD EPYC 7763 64-Core Processor; filesystem/media: /dev/sda1 ext4 (rotational). Allocation: 2 CPU cores, 6554 MiB process-tree memory; 512 MiB worker heap, 128 MiB direct memory; free-disk reserve 17.03 GiB.

Execution exit code: 0.

Redline diagnostics (unqualified). One fresh JVM per mode; record bytes 1024, 256-record batches, 2s warmup and 2s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

Consumption includes broadcast fanout. Peak production is a short-window observation, not sustained capacity. The last measured window can precede the terminal failure; a CPU_HOLD row is a partial window.

| Mode | Peak produced/s | Last measured pairs | Stop stage / attempted pairs | Stop reason | Peak CPU % | Max reported heap MiB | Worst p99 ms |
| --- | ---: | ---: | --- | --- | ---: | ---: | ---: |
| FILE | 355,361 | 256 | 9 / 256 | BROKER_OR_CLIENT_LIMIT | 100.0% | 416.0 | 1073.742 |

| Mode | Stage | Pairs | Duration s | Produced/s | Fanout consumed/s | Average CPU % | Reported heap MiB | p99 ms | Window |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 1 | 2.01 | 112,933 | 98,513 | 96.9% | 236.0 | 33.554 | ramp |
| FILE | 2 | 2 | 2.02 | 133,377 | 155,881 | 96.2% | 192.0 | 67.109 | ramp |
| FILE | 3 | 4 | 2.04 | 178,835 | 214,100 | 95.6% | 414.0 | 67.109 | ramp |
| FILE | 4 | 8 | 2.04 | 254,264 | 321,884 | 95.9% | 326.0 | 67.109 | ramp |
| FILE | 5 | 16 | 2.03 | 294,588 | 386,229 | 96.4% | 322.0 | 67.109 | ramp |
| FILE | 6 | 32 | 2.01 | 355,361 | 452,684 | 98.4% | 370.0 | 67.109 | ramp |
| FILE | 7 | 64 | 2.01 | 346,909 | 421,138 | 97.6% | 316.0 | 134.218 | ramp |
| FILE | 8 | 128 | 2.07 | 191,025 | 181,628 | 94.8% | 376.0 | 536.871 | ramp |
| FILE | 9 | 256 | 0.83 | 57,513 | 39,982 | 97.4% | 416.0 | 1073.742 | BROKER_OR_CLIENT_LIMIT |

FILE: JDK 26.0.2.1; Linux; filesystem ext4; 2 CPU budget; 512 MiB maximum heap.


Resource-enforced redline diagnostics (unqualified)

Source revision `c50491838aa195c37eba13631233c787bae06281`; enforcement: systemd-cgroup-v2.

Host: AMD EPYC 9V74 80-Core Processor; filesystem/media: /dev/sda1 ext4 (rotational). Allocation: 2 CPU cores, 6554 MiB process-tree memory; 512 MiB worker heap, 128 MiB direct memory; free-disk reserve 17.03 GiB.

Execution exit code: 0.

Redline diagnostics (unqualified). One fresh JVM per mode; record bytes 1024, 256-record batches, 2s warmup and 2s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.

Consumption includes broadcast fanout. Peak production is a short-window observation, not sustained capacity. The last measured window can precede the terminal failure; a CPU_HOLD row is a partial window.

| Mode | Peak produced/s | Last measured pairs | Stop stage / attempted pairs | Stop reason | Peak CPU % | Max reported heap MiB | Worst p99 ms |
| --- | ---: | ---: | --- | --- | ---: | ---: | ---: |
| IN_MEMORY | 335,284 | 1024 | 12 / 2048 | JVM_OOM | 100.0% | 506.0 | 1073.742 |

| Mode | Stage | Pairs | Duration s | Produced/s | Fanout consumed/s | Average CPU % | Reported heap MiB | p99 ms | Window |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 1 | 1 | 2.01 | 335,284 | 319,713 | 95.4% | 404.0 | 4.194 | ramp |
| IN_MEMORY | 2 | 2 | 2.00 | 331,015 | 631,509 | 95.7% | 240.0 | 4.194 | ramp |
| IN_MEMORY | 3 | 4 | 2.02 | 225,890 | 845,600 | 97.3% | 324.0 | 33.554 | ramp |
| IN_MEMORY | 4 | 8 | 2.03 | 141,664 | 1,015,741 | 96.7% | 222.0 | 33.554 | ramp |
| IN_MEMORY | 5 | 16 | 2.03 | 85,973 | 1,122,939 | 98.2% | 380.0 | 33.554 | ramp |
| IN_MEMORY | 6 | 32 | 2.01 | 47,690 | 1,278,182 | 97.1% | 372.0 | 67.109 | ramp |
| IN_MEMORY | 7 | 64 | 2.05 | 27,496 | 1,164,474 | 96.8% | 290.0 | 67.109 | ramp |
| IN_MEMORY | 8 | 128 | 11.20 | 8,964 | 1,080,223 | 96.5% | 438.0 | 134.218 | ramp |
| IN_MEMORY | 9 | 256 | 2.04 | 19,060 | 904,861 | 95.7% | 314.0 | 134.218 | ramp |
| IN_MEMORY | 10 | 512 | 11.12 | 4,628 | 801,989 | 95.0% | 432.0 | 536.871 | ramp |
| IN_MEMORY | 11 | 1024 | 2.01 | 21,027 | 394,922 | 89.1% | 506.0 | 1073.742 | ramp |

IN_MEMORY: JDK 26.0.2.1; Linux; filesystem ext4; 2 CPU budget; 512 MiB maximum heap.

<!-- benchmark-redline:end -->

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
