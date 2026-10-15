# Setu

Durable storage now defaults to OpenHFT Chronicle Queue. The benchmark badges below retain their recorded source revisions and predate this consolidation; they do not qualify the new engine.

[CI workflow](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
<!-- benchmark-metrics-badges:start -->
[![in-memory: 500.00K/s target, 285.90K/s CI, 57% unqualified](benchmark-badges/in-memory-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38776097001)
[![file: 250.00K/s target, 83.91K/s CI, 34% unqualified](benchmark-badges/file-sustained.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38776097001)
<!-- benchmark-metrics-badges:end -->

<!-- benchmark-target-diagnostics:start -->
[![memory p99: 12952.01 ms at target (CI)](benchmark-badges/memory-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38776097001)
[![memory resources: 100% CPU, 256 MiB heap (CI)](benchmark-badges/memory-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38776097001)
[![memory JFR: 0.95 GiB/s alloc, 45.5 ms GC (CI)](benchmark-badges/memory-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38776097001)
[![file p99: 20132.66 ms at target (CI)](benchmark-badges/file-target-latency.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38776097001)
[![file resources: 30% CPU, 128 MiB heap (CI)](benchmark-badges/file-target-resources.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38776097001)
[![file JFR: 0.33 GiB/s alloc, 4.4 ms GC (CI)](benchmark-badges/file-target-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38776097001)
<!-- benchmark-target-diagnostics:end -->

Badges show the latest target-load measurements against **500K records/s in-memory** and **250K records/s durable file** at 1 KiB per record, with their source and qualification status. Each mode needs three independent repetitions at 50%, 80% and 100% of target, 30 measured seconds per stage, at least 95% unique consumption and offered throughput, zero errors/timeouts, measured HDR latency, stable heap within 1 GiB, and average CPU at most 85%. Tests, full target benchmarks and JFR run automatically on pushes and pull requests; default-branch runs update the badges. Unqualified or incomplete CI runs are shown explicitly. File acknowledgments follow a force of every touched segment in each group commit.

## Latest node-target benchmark details

<!-- benchmark-capacity-details:start -->
Latest source: **CI**, revision `0838326b50c76a21b25a0f8143ca8eb62873c839`. [Benchmark run/report](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38776097001).

Runner: AMD EPYC 9V45 96-Core Processor; 1.6 enforced CPU cores; 128 MiB worker heap; filesystem/media /dev/nvme0n1p1 ext4 (non-rotational).

Runner: AMD EPYC 9V74 80-Core Processor; 1.6 enforced CPU cores; 256 MiB worker heap; filesystem/media /dev/sda1 ext4 (rotational).

# Node-target measurements

Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, HDR p99 within mode/load limits, CPU <=85%, heap <=1 GiB and heap slope <=1 MiB/s.

| Mode | Target/s | Median measured/s | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| IN_MEMORY | 500,000 | 285,901 | 12952.011 | 256 | 99.5 | False |
| FILE | 250,000 | 83,908 | 20132.659 | 128 | 29.7 | False |

| Mode | Repeat | Load | Measured/s | Consumed/s | p99 ms | Heap slope MiB/s | Healthy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| FILE | 1 | 50% | 74,138 | 58,121 | 12113.150 | 0.050 | False |
| FILE | 1 | 80% | 69,137 | 55,893 | 19478.348 | 0.132 | False |
| FILE | 1 | 100% | 84,181 | 74,889 | 19713.229 | 0.110 | False |
| FILE | 2 | 50% | 84,122 | 68,753 | 9722.397 | 0.085 | False |
| FILE | 2 | 80% | 83,063 | 64,051 | 17381.196 | 0.049 | False |
| FILE | 2 | 100% | 77,559 | 64,239 | 20132.659 | 0.033 | False |
| FILE | 3 | 50% | 83,541 | 65,664 | 9831.449 | 0.190 | False |
| FILE | 3 | 80% | 83,849 | 76,570 | 17263.755 | 0.139 | False |
| FILE | 3 | 100% | 83,908 | 75,324 | 19662.897 | 0.086 | False |
| IN_MEMORY | 1 | 50% | 249,993 | 251,034 | 218.104 | -0.202 | False |
| IN_MEMORY | 1 | 80% | 294,025 | 294,007 | 7868.514 | -0.066 | False |
| IN_MEMORY | 1 | 100% | 285,901 | 285,892 | 12725.518 | 0.354 | False |
| IN_MEMORY | 2 | 50% | 249,993 | 250,991 | 151.912 | -0.184 | False |
| IN_MEMORY | 2 | 80% | 282,564 | 282,556 | 8715.764 | -0.312 | False |
| IN_MEMORY | 2 | 100% | 282,163 | 282,189 | 12952.011 | -0.110 | False |
| IN_MEMORY | 3 | 50% | 249,993 | 251,034 | 149.422 | -0.053 | False |
| IN_MEMORY | 3 | 80% | 316,365 | 316,348 | 6224.347 | 0.258 | False |
| IN_MEMORY | 3 | 100% | 298,522 | 298,513 | 11962.155 | -0.091 | False |

Qualification failures (completed measurements are retained):

| Mode | Repeat | Load | Measured limit failures |
| --- | ---: | ---: | --- |
| FILE | 1 | 50% | consumption 78.4% <95%; throughput 74,138/s <95% of offered 125,000/s; p99 12113.150 ms outside [0,10) ms |
| FILE | 1 | 80% | consumption 80.8% <95%; throughput 69,137/s <95% of offered 200,000/s; p99 19478.348 ms outside [0,100) ms |
| FILE | 1 | 100% | consumption 89.0% <95%; throughput 84,181/s <95% of offered 250,000/s; p99 19713.229 ms outside [0,100) ms |
| FILE | 2 | 50% | consumption 81.7% <95%; throughput 84,122/s <95% of offered 125,000/s; p99 9722.397 ms outside [0,10) ms |
| FILE | 2 | 80% | consumption 77.1% <95%; throughput 83,063/s <95% of offered 200,000/s; p99 17381.196 ms outside [0,100) ms |
| FILE | 2 | 100% | consumption 82.8% <95%; throughput 77,559/s <95% of offered 250,000/s; p99 20132.659 ms outside [0,100) ms |
| FILE | 3 | 50% | consumption 78.6% <95%; throughput 83,541/s <95% of offered 125,000/s; p99 9831.449 ms outside [0,10) ms |
| FILE | 3 | 80% | consumption 91.3% <95%; throughput 83,849/s <95% of offered 200,000/s; p99 17263.755 ms outside [0,100) ms |
| FILE | 3 | 100% | consumption 89.8% <95%; throughput 83,908/s <95% of offered 250,000/s; p99 19662.897 ms outside [0,100) ms |
| IN_MEMORY | 1 | 50% | p99 218.104 ms outside [0,5) ms |
| IN_MEMORY | 1 | 80% | throughput 294,025/s <95% of offered 400,000/s; p99 7868.514 ms outside [0,50) ms; CPU 99.4% outside 0–85% of allocated budget |
| IN_MEMORY | 1 | 100% | throughput 285,901/s <95% of offered 500,000/s; p99 12725.518 ms outside [0,50) ms; CPU 99.3% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 50% | p99 151.912 ms outside [0,5) ms |
| IN_MEMORY | 2 | 80% | throughput 282,564/s <95% of offered 400,000/s; p99 8715.764 ms outside [0,50) ms; CPU 99.4% outside 0–85% of allocated budget |
| IN_MEMORY | 2 | 100% | throughput 282,163/s <95% of offered 500,000/s; p99 12952.011 ms outside [0,50) ms; CPU 99.4% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 50% | p99 149.422 ms outside [0,5) ms |
| IN_MEMORY | 3 | 80% | throughput 316,365/s <95% of offered 400,000/s; p99 6224.347 ms outside [0,50) ms; CPU 99.5% outside 0–85% of allocated budget |
| IN_MEMORY | 3 | 100% | throughput 298,522/s <95% of offered 500,000/s; p99 11962.155 ms outside [0,50) ms; CPU 99.5% outside 0–85% of allocated budget |
<!-- benchmark-capacity-details:end -->

## Historical capacity diagnostics

These are the previous short exhaustion observations, including consumer fanout. They do not qualify as sustained throughput or target progress.

<!-- benchmark-historical-badges:start -->
[![memory peak rates: 433.95K prod/s · 872.32K consumed/s](benchmark-badges/memory-throughput.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![file peak rates: 205.50K prod/s · 178.53K consumed/s](benchmark-badges/file-throughput.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![memory capacity: 2,048×2,048 · OS limit · peak CPU 100% · 1252 MB · p99 2.1s](benchmark-badges/memory-capacity.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![file capacity: 256×256 · broker/client limit · peak CPU 100% · 750 MB · p99 1.1s](benchmark-badges/file-capacity.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![memory JFR: stage 12 · 1.31 GiB/s alloc · 1.8ms GC](benchmark-badges/memory-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![file JFR: stage 9 · 1.92 GiB/s alloc · 0.0ms GC](benchmark-badges/file-jfr.svg)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
<!-- benchmark-historical-badges:end -->

**Setu** is a distributed message broker built from scratch in **Java 26**, inspired by systems such as Kafka.

The project focuses on the engineering problems behind high-throughput messaging systems:

* Networking
* Concurrency
* Persistent storage
* Memory management
* Partitioning
* Consumer coordination
* Backpressure
* Failure recovery
* Security
* Performance benchmarking

The goal is not to reproduce Kafka feature-for-feature, but to understand and implement the core mechanisms behind a production-style messaging system.

---

## 1. Goals

Setu aims to provide:

* High-throughput message ingestion
* Batched produce requests that amortize protocol and durable-sync overhead
* High concurrent connection handling
* Low-latency message consumption
* Topics and partitions
* Concurrent producers and consumers
* Consumer groups and offsets
* Persistent append-only logs
* Efficient file-backed access using Java FFM / `MemorySegment`
* Crash and partial-write recovery
* Backpressure under slow persistence
* Configurable acknowledgement semantics
* Segment rotation and retention
* TLS / mTLS secured communication
* Authentication and authorization
* Client quotas and rate limiting
* Secure credential and certificate lifecycle
* Observability and metrics
* Performance and failure benchmarking

The single-node throughput targets are **500,000 records/s in memory** (~488 MiB/s at 1 KiB) and **250,000 records/s with file storage** (~244 MiB/s), with stretch targets of 1M/s and 500K/s respectively. The full CI profile gates 1 KiB runs at 200K/s in memory and 150K/s with file storage, subject to the same sustainability criteria. Broker and load clients share the host. Performance is reported per allocated CPU core and with hardware, durability, backlog and resource usage; targets are not claims of measured performance. See [benchmark profiles and qualification](setu-benchmark/README.md).

The [historical JFR and node-target measurements](setu-benchmark/README.md#earlier-measurements) records three 30-second repetitions at the offered targets, including actual throughput, latency, heap and earlier failed attempts. Automatic CI replaces the locally labeled badge data with measurements from its resource-enforced target profile.

**OpenHFT Chronicle Queue 2026.6 is the default durable storage engine.** The custom file backends and optional comparison module have been consolidated into the production adapter. File acknowledgments follow a JDK force of touched mapped files, indexes and roll metadata. See [storage, JVM options and offline migration](setu-storage/README.md). The [15 October optimization measurements](setu-benchmark/README.md#15-october-optimization-measurements) and existing local badges describe the earlier custom engine; they do not qualify Chronicle performance.

The project is primarily concerned with the trade-offs between:

```text
Networking
    ↓
Concurrency
    ↓
Memory
    ↓
Persistence
    ↓
Durability
    ↓
Throughput
    ↓
Latency
    ↓
Failure Recovery
```

## Build and test

The root `pom.xml` is the Maven reactor for all modules. Use the checked-in Maven Wrapper from the repository root, with a Java 26 JDK installed:

```powershell
.\mvnw.cmd test
.\mvnw.cmd -pl setu-security test
.\mvnw.cmd -pl setu-integration-tests -am '-Dtest=*IntegrationTests' -Dsurefire.failIfNoSpecifiedTests=false test
.\mvnw.cmd -pl setu-benchmark -am '-Dtest=*ChaosTests,!ProgressiveCapacityChaosTests,QuickBrokerChaosBenchmarkTests' -Dsurefire.failIfNoSpecifiedTests=false test
.\mvnw.cmd -pl setu-benchmark -am '-Dtest=ProgressiveCapacityChaosTests' -Dsurefire.failIfNoSpecifiedTests=false test
```

On macOS/Linux, use `bash ./mvnw test` (or `./mvnw test` when the checkout marks the script executable).

The first invocation downloads the pinned Maven distribution. The wrapper supplies Maven; it does not install Java.

The wrapper JVM, forked tests, and broker launchers use `-XX:+UseZGC -XX:+UseStringDeduplication`. Benchmark child JVMs verify both options at startup. Java 26 supports AOT caching with ZGC: after packaging, run `./scripts/start-broker.ps1 --train-aot` (PowerShell) or `bash ./scripts/start-broker.sh --train-aot` (macOS/Linux). This trains all five production modules through plaintext and the full mTLS/authentication/authorization/quota path, using 1,024-byte payloads in both storage modes. Subsequent launcher runs use the generated cache. Tests can run without AOT. See [the server launch instructions](setu-server/README.md#standalone-jar).

---

# 2. High-Level Architecture

```text
                         ┌──────────────────┐
                         │     Producer     │
                         └────────┬─────────┘
                                  │
                             TCP + TLS
                                  │
                                  ▼
                         ┌──────────────────┐
                         │   Setu Server    │
                         │                  │
                         │ Connection Mgmt  │
                         │ Protocol Codec   │
                         │ Request Dispatch │
                         └────────┬─────────┘
                                  │
                                  ▼
                         ┌──────────────────┐
                         │    Security      │
                         │                  │
                         │ Authentication   │
                         │ Authorization    │
                         │ Rate Limiting    │
                         │ Protocol Checks  │
                         └────────┬─────────┘
                                  │
                                  ▼
                         ┌──────────────────┐
                         │    Setu Core     │
                         │                  │
                         │ Topics           │
                         │ Partitions       │
                         │ Producers        │
                         │ Consumers        │
                         │ Consumer Groups  │
                         │ Offsets          │
                         └────────┬─────────┘
                                  │
                                  ▼
                         ┌──────────────────┐
                         │   Log Manager    │
                         │                  │
                         │ Append           │
                         │ Fetch            │
                         │ Offset Mapping   │
                         │ Rotation         │
                         │ Recovery         │
                         └────────┬─────────┘
                                  │
                     ┌────────────┴────────────┐
                     │                         │
                     ▼                         ▼
             ┌──────────────┐          ┌──────────────┐
             │ FFM /        │          │ Append-only  │
             │ MemorySegment│◄────────►│ Segment File │
             └──────────────┘          └──────────────┘
                     │
                     ▼
                 Consumers


        ┌────────────────────────────────────┐
        │           PostgreSQL               │
        │          Control Plane             │
        │                                    │
        │ Topics                             │
        │ Partitions                         │
        │ Consumer Groups                    │
        │ Consumer Offsets                   │
        │ Broker Metadata                    │
        └────────────────────────────────────┘
```

---

# 3. Core Storage Principle

Setu has two distinct planes.

### Data Plane

Contains the actual message payloads.

```text
Producer
   │
   ▼
Setu Broker
   │
   ▼
Partition
   │
   ▼
Append-only Log
   │
   ▼
Segment Files
```

### Control Plane

Contains metadata required to operate the broker.

```text
PostgreSQL
   │
   ├── Topics
   ├── Partitions
   ├── Consumer Groups
   ├── Consumer Offsets
   └── Broker Metadata
```

**Message payloads are not stored in PostgreSQL.**

PostgreSQL is the control-plane metadata store.

The append-only log files are the durable source of truth for message data.

---

# 4. FFM / MemorySegment (planned)

The target storage design may use Java's **Foreign Function & Memory API (FFM)** for efficient interaction with file-backed and off-heap memory. The current `setu-storage` implementation does not use FFM or `MemorySegment`; it uses `FileChannel` and heap byte arrays.

FFM is an access mechanism, not the persistence layer.

Conceptually:

```text
                    Setu Storage
                         │
                         ▼
                  Segment File
                         │
                         │ file mapping / access
                         ▼
                 MemorySegment
                         │
                         ▼
                Message Records
```

If adopted after benchmarking, this would provide a path for:

* File-backed memory access
* Off-heap memory usage
* Large buffer management
* Reduced heap copying
* Sequential log access

The project should benchmark whether this approach provides measurable improvements rather than assuming that FFM is automatically faster.

---

# 5. Topics

A topic represents a logical stream of messages.

Example:

```text
orders
payments
notifications
events
```

A topic can contain multiple partitions.

```text
orders

├── partition-0
├── partition-1
├── partition-2
└── partition-3
```

---

# 6. Partitions

Partitions provide independent append streams and parallelism.

Example:

```text
orders

partition-0 ─────► consumer
partition-1 ─────► consumer
partition-2 ─────► consumer
partition-3 ─────► consumer
```

Ordering is guaranteed **within a partition**.

Setu does not attempt to provide global ordering across all partitions.

---

# 7. Message Offsets

Every message receives a monotonically increasing offset within its partition.

```text
partition-0

offset 100 → message A
offset 101 → message B
offset 102 → message C
offset 103 → message D
```

Consumers use offsets to determine their position in a partition.

---

# 8. Message Lifecycle

Producer flow:

```text
Producer
   │
   │ PRODUCE
   ▼
TCP Server
   │
   ▼
Authentication
   │
   ▼
Authorization
   │
   ▼
Setu Core
   │
   ▼
Partition
   │
   ▼
Log Manager
   │
   ▼
FFM / MemorySegment
   │
   ▼
Append-only Segment
   │
   ▼
Durability Boundary
   │
   ▼
ACK
```

Consumer flow:

```text
Consumer
   │
   │ FETCH(topic, partition, offset)
   ▼
TCP Server
   │
   ▼
Authentication
   │
   ▼
Authorization
   │
   ▼
Setu Core
   │
   ▼
Log Manager
   │
   ├── Resolve segment
   ├── Resolve file position
   └── Read record
   │
   ▼
MemorySegment / File-backed region
   │
   ▼
Consumer
```

Consumers do not directly interact with `MemorySegment` or segment files.

They operate on logical topics, partitions, and offsets.

---

# 9. Persistent Log

File mode uses OpenHFT Chronicle Queue with native daily rolled `.cq4` files under `<data.dir>/<topic>/partition-<id>/`. Chronicle owns mappings and physical indexes; Setu stores versioned batch documents and exposes dense logical offsets across rolls and restart. The bounded MPSC writer shares durable flushes across adjacent requests.

See the [storage README](setu-storage/README.md) for the format, force-before-ack boundary, recovery and migration. Old length-prefixed `.log` data requires an offline import into a fresh data root.

---

# 10. Queue Lifecycle

Chronicle rolls each partition daily. `queue.block.bytes` configures mapping blocks with a 64 MiB minimum/default; it does not limit daily file size. Closed roll files remain available for full-history reads. Automatic file retention and deletion remain future work.

---

# 11. Offset → Storage Mapping

The storage layer owns the mapping between logical offsets and physical storage.

```text
Consumer
   │
   │ FETCH offset=105
   ▼
Log Manager
   │
   ▼
Segment Index
   │
   ├── segment-0002
   └── position=18432
   │
   ▼
MemorySegment
   │
   ▼
Record
```

This responsibility must remain inside the storage layer.

The core messaging layer should not understand physical file offsets or memory mappings.

---

# 12. Backpressure

Persistence can become slower than incoming traffic.

Example:

```text
Producer rate     = 100 MB/s
Persistence rate  = 60 MB/s
```

Without backpressure:

```text
Producer
   │
   ▼
Unbounded Buffer
   │
   ▼
Memory ↑
   │
   ▼
OOM
```

Setu should use bounded buffering:

```text
Producer
   │
   ▼
Bounded Buffer
   │
   ├── capacity available
   │       │
   │       ▼
   │    accept
   │
   └── capacity exhausted
           │
           ▼
       throttle /
       block /
       reject
```

The behavior should be configurable.

Metrics should expose:

* Queue depth
* Buffer utilization
* Producer throttling
* Rejected requests
* Persistence latency

---

# 13. Acknowledgement Semantics

Setu should make acknowledgement semantics explicit.

Potential modes:

```text
ACK_AFTER_APPEND
ACK_AFTER_PERSIST
```

`ACK_AFTER_APPEND` means the broker has successfully appended the message to its active storage path.

`ACK_AFTER_PERSIST` represents a stronger durability guarantee.

The implementation must define the exact durability boundary.

Writing into the operating-system page cache should not automatically be described as surviving a machine crash.

Where strong durability is required, the storage implementation should use the appropriate file synchronization mechanism.

---

# 14. Consumer Groups

Consumer groups allow multiple consumers to process partitions cooperatively.

Example:

```text
Topic: orders

Partition 0 ─────► Consumer A
Partition 1 ─────► Consumer B
Partition 2 ─────► Consumer C
Partition 3 ─────► Consumer A
```

The broker must handle:

* Consumer registration
* Partition assignment
* Offset commits
* Consumer heartbeats
* Consumer failure
* Rebalancing
* Duplicate processing
* Consumer recovery

---

# 15. Consumer Offset Storage

Offsets are control-plane state.

Example:

```text
consumer_offsets

group_id
topic
partition
offset
updated_at
```

Conceptually:

```text
Consumer
   │
   ▼
Process message
   │
   ▼
Commit offset
   │
   ▼
PostgreSQL
```

The exact commit semantics should be designed around the desired delivery guarantee.

---

# 16. Security

Security is a first-class Setu subsystem.

`setu-server` integrates TLS/mTLS, per-request authorization and quotas, a versioned protocol, and structured request errors. It uses the default-deny `InMemoryAuthorizer` and protocol metadata resolver from its current security/server packages, plus a file-backed broker path in `setu-core` and `setu-storage`. ACL grants, group membership, and committed offsets are currently process-local; PostgreSQL integration remains future work. See `setu-server/README.md` and `setu-security/README.md` for implementation details and limitations.

Setu must secure both:

```text
Who can connect?
```

and:

```text
What can they do?
```

The security architecture consists of:

* TLS
* mTLS
* Authentication
* Authorization
* Topic-level access control
* Administrative permissions
* Rate limiting
* Client quotas
* Certificate lifecycle
* Credential management
* Protocol validation

---

# 17. TLS / mTLS

Client-to-broker communication should support TLS.

```text
Producer
   │
   │ TLS
   ▼
Setu Broker
```

For trusted service-to-service environments, mTLS can be enabled:

```text
Producer / Service
       │
       │ mTLS
       ▼
  Setu Broker
```

With mTLS, both sides authenticate using certificates.

```text
Client Certificate
        │
        ▼
       CA
        │
        ▼
Setu Trust Store
```

Certificate identity can be associated with a Setu principal.

---

# 18. Authentication

Setu should support multiple authentication mechanisms through an abstraction.

Conceptually:

```text
AuthenticationProvider
        │
        ├── ApiKeyAuthenticator
        ├── JwtAuthenticator
        └── MtlsAuthenticator
```

This prevents authentication logic from being embedded into the broker protocol.

The initial implementation can start with one mechanism and add others as the system matures.

---

# 19. Authorization

Authentication answers:

```text
Who are you?
```

Authorization answers:

```text
What are you allowed to do?
```

Setu should support permissions such as:

```text
READ
WRITE
CREATE_TOPIC
DELETE_TOPIC
VIEW_METADATA
MANAGE_CONSUMER_GROUP
ADMIN
```

Example:

```text
Principal: payment-service

orders:
    READ
    WRITE

payments:
    WRITE

admin:
    none
```

A possible authorization model:

```text
Principal
   │
   ▼
Role
   │
   ▼
Permissions
   │
   ▼
Resource
```

Resources may include:

```text
topic
partition
consumer-group
broker
```

---

# 20. Administrative Security

Administrative operations must be separated from normal producer/consumer operations.

Examples:

```text
CREATE_TOPIC
DELETE_TOPIC
ALTER_TOPIC
VIEW_BROKER_METADATA
MANAGE_CONSUMER_GROUP
UPDATE_CONFIGURATION
```

These operations require elevated permissions.

The normal producer path must never implicitly grant administrative access.

---

# 21. Rate Limiting and Quotas

Security also includes protection against resource exhaustion.

Setu should support configurable client quotas such as:

```text
maximum messages/sec
maximum bytes/sec
maximum connections
maximum request size
maximum outstanding requests
```

Example:

```text
Client
  │
  ▼
Authentication
  │
  ▼
Authorization
  │
  ▼
Quota Check
  │
  ├── allowed → process
  │
  └── exceeded → throttle / reject
```

This works together with the storage backpressure mechanism.

---

# 22. Protocol Validation

The broker must not trust incoming network data.

Requests should be validated for:

* Frame size
* Message size
* Topic name
* Partition
* Offset
* Command type
* Payload length
* Request structure
* Authentication state

Malformed requests should be rejected without allowing them to destabilize the broker.

---

# 23. Credential and Certificate Lifecycle

Security credentials should follow a lifecycle:

```text
Generate
   │
   ▼
Provision
   │
   ▼
Validate
   │
   ▼
Rotate
   │
   ▼
Revoke
```

Development certificates can be generated using a local CA.

Example:

```text
certs/
├── ca.crt
├── ca.key
├── broker.crt
├── broker.key
├── client.crt
└── client.key
```

Private keys must never be committed to source control.

For containerized deployments, credentials should be provided through secure external configuration mechanisms such as Kubernetes Secrets.

---

# 24. Crash Recovery

Setu must recover correctly after broker failure.

Recovery flow:

```text
Broker Crash
     │
     ▼
Restart
     │
     ▼
Discover Log Segments
     │
     ▼
Read Segment Metadata
     │
     ▼
Identify Active Segment
     │
     ▼
Validate Records
     │
     ▼
Detect Partial Tail
     │
     ▼
Recover Valid Records
     │
     ▼
Resume Append
```

Potential failure cases:

* Process crash during append
* Partial record
* Truncated segment
* Interrupted segment rotation
* Broker restart
* Corrupted metadata
* Consumer restart

---

# 25. Concurrency Model

Setu contains several concurrent workloads:

* Network connections
* Producers
* Consumers
* Partition appenders
* Segment rotation
* Background persistence
* Consumer-group coordination
* Offset commits
* Recovery

Potential Java primitives include:

* `ExecutorService`
* Virtual threads where appropriate
* `ReentrantLock`
* `StampedLock`
* `Atomic*`
* Concurrent collections
* Bounded queues

The goal is not to eliminate every lock.

The goal is to understand:

```text
Where is contention?
Why does it exist?
What synchronization is required?
What does it cost?
```

Concurrency decisions should be validated using benchmarks and profiling.

---

# 26. Network Protocol

The initial Setu protocol uses TCP.

Conceptually:

```text
┌──────────────┐
│ TCP Server   │
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ Frame Codec  │
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ Authentication
│ / Security   │
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ Dispatcher   │
└──────┬───────┘
       │
       ▼
   Setu Core
```

Potential commands:

```text
PRODUCE
FETCH
COMMIT_OFFSET
JOIN_GROUP
LEAVE_GROUP
HEARTBEAT
METADATA
CREATE_TOPIC
```

TCP packet boundaries must not be treated as message boundaries.

Setu therefore needs an explicit framing protocol.

---

# 27. Observability

Setu should expose metrics including:

```text
messages/sec
bytes/sec

producer latency
consumer latency

p50
p95
p99

active connections
active partitions

queue depth
buffer utilization

segment count
segment rotation rate

consumer lag

append failures
recovery time

authentication failures
authorization failures
rate-limit events

heap usage
off-heap usage
GC activity
CPU usage
```

Security metrics should include:

```text
authentication failures
authorization failures
TLS failures
rejected requests
rate-limit violations
```

---

# 28. Benchmarking

The benchmark module should measure actual system behavior.

## Throughput

```text
messages/sec
MB/sec
```

## Latency

```text
p50
p95
p99
```

## Connection Scalability

```text
100 connections
1,000 connections
10,000 connections
```

## Message Size

```text
128 B
1 KB
10 KB
100 KB
```

## Storage

Compare relevant implementations such as:

```text
heap-based buffering
vs
FFM / MemorySegment
```

Measure:

```text
throughput
latency
allocation
GC
CPU
memory
```

## Failure

Measure:

```text
broker crash
consumer crash
producer disconnect
partial append
segment recovery
```

## Security

Measure the overhead of:

```text
TLS
mTLS
authentication
authorization
```

The objective is to understand the performance/security trade-off rather than simply claiming that security exists.

---

# 29. Target Project Structure

The tree below is the intended long-term layout, not a claim that all listed packages and classes exist today. See the current implementation snapshot in section 31 and each module README for the shipped packages.

```text
setu/
│
├── pom.xml
├── README.md
│
├── setu-core/
│   ├── pom.xml
│   └── src/
│       ├── main/java/
│       │   └── io/setu/core/
│       │       ├── broker/
│       │       ├── topic/
│       │       ├── partition/
│       │       ├── producer/
│       │       ├── consumer/
│       │       ├── group/
│       │       ├── offset/
│       │       └── protocol/
│       │
│       └── test/java/
│
├── setu-storage/
│   ├── pom.xml
│   └── src/
│       ├── main/java/
│       │   └── io/setu/storage/
│       │       ├── log/
│       │       ├── segment/
│       │       ├── memory/
│       │       ├── ffm/
│       │       ├── index/
│       │       ├── recovery/
│       │       ├── retention/
│       │       └── persistence/
│       │
│       └── test/java/
│
├── setu-security/
    ├── pom.xml
    └── src/
        ├── main/java/io/setu/security/
        │
        ├── authentication/
        │   ├── AuthenticationProvider.java
        │   ├── AuthenticationRequest.java
        │   ├── AuthenticationResult.java
        │   ├── AuthenticationMethod.java
        │   ├── AuthenticationException.java
        │   └── MtlsAuthenticator.java
        │
        ├── authorization/
        │   ├── Authorizer.java
        │   ├── AuthorizationContext.java
        │   ├── AccessPolicy.java
        │   ├── Permission.java
        │   └── Role.java
        │
        ├── tls/
        │   ├── TlsConfiguration.java
        │   ├── KeyManagerFactory.java
        │   └── TrustManagerFactory.java
        │
        ├── certificates/
        │   ├── CertificateValidator.java
        │   └── CertificateIdentity.java
        │
        ├── credentials/
        │   ├── CredentialProvider.java
        │   └── Credential.java
        │
        └── quota/
            ├── RateLimiter.java
            ├── QuotaManager.java
            └── QuotaPolicy.java
        │
        └── test/java/
│
├── setu-server/
│   ├── pom.xml
│   └── src/
│       ├── main/java/
│       │   └── io/setu/server/
│       │       ├── tcp/
│       │       ├── codec/
│       │       ├── connection/
│       │       └── dispatcher/
│       │
│       └── test/java/
│
├── setu-client/
│   ├── pom.xml
│   └── src/
│       ├── main/java/
│       │   └── io/setu/client/
│       │       ├── producer/
│       │       ├── consumer/
│       │       ├── protocol/
│       │       └── connection/
│       │
│       └── test/java/
│
├── setu-benchmark/
│   ├── pom.xml
│   └── src/test/java/io/setu/benchmark/chaos/
│       ├── BrokerCrashChaosTests.java
│       ├── BrokerCrashWorker.java
│       ├── BrokerLimitsChaosTests.java
│       ├── MultiConsumerChaosTests.java
│       ├── ProgressiveCapacityChaosWorker.java
│       ├── QuickBrokerChaosBenchmarkTests.java
│       └── ProgressiveCapacityChaosTests.java
│
├── setu-integration-tests/
│   ├── pom.xml
│   ├── README.md
│   └── src/test/java/io/setu/integration/
│
├── config/
│   └── setu.yaml
│
├── certs/
│   └── .gitkeep
│
├── scripts/
│   ├── generate-certs.sh
│   ├── generate-certs.ps1
│   ├── start-broker.sh
│   ├── stop-broker.sh
│   └── benchmark.sh
│
├── docker/
│   └── Dockerfile
│
├── deploy/
│   └── kubernetes/
│       ├── broker/
│       ├── config/
│       └── secrets/
│
└── data/
    └── .gitkeep
```

---

# 30. Module Responsibilities

| Module           | Responsibility                                                     |
| ---------------- | ------------------------------------------------------------------ |
| `setu-core`      | Current: broker API/implementation, v1 protocol values/codec, and single-record plus batch produce. Target: full topics, partitions, producers, consumers, groups |
| `setu-storage`   | Current: in-memory logs and default Chronicle Queue partition logs, native mapping/indexes, dense logical offsets, group commit and startup recovery. Target: file retention, checksums, replication |
| `setu-security`  | Current: TLS configuration/authentication, ACL interfaces and in-memory authorizer, quotas |
| `setu-server`    | Current: TCP/TLS connections, framing, request authorization, dispatch, plaintext selector transport |
| `setu-client`    | Current: synchronous protocol v1 socket client. Target: producer/consumer APIs |
| `setu-benchmark` | Real-TCP sustained throughput, fixed-load HDR latency, recovery and chaos tests; concurrency is capped near twice the CPU budget. The legacy exhaustion ramp remains an explicit diagnostic. |
| `setu-integration-tests` | Functional cross-module TCP and mTLS requirement tests for both storage modes; no saturation or chaos workloads |

# 31. Current Implementation Snapshot

This section distinguishes the current integrated implementation from the broader goals above. The architecture remains layered: the server owns transport and request processing, `setu-security` owns reusable security primitives, `setu-core` owns broker/protocol APIs, and `setu-storage` owns partition-log implementations. The broker selects in-memory or local file storage; message payloads are not stored in PostgreSQL.

| Area | Implemented in this checkout | Not implemented yet |
| --- | --- | --- |
| Protocol and transport | Version 1 commands, framed TCP, strict request decoding, correlation IDs, structured errors, single and batched produce, optional member-aware fetch/ack fields with legacy-body support, TLS/mTLS blocking transport, plaintext selector multiplexing | Selector TLS, asynchronous/pipelined client API, negotiated protocol compatibility |
| Security | mTLS identity, command/resource authorization mapping, exact default-deny in-memory ACLs, per-principal token-bucket quota checks | Persistent/cluster ACLs, wildcard policy, credential issuance/rotation, distributed quotas |
| Broker | Topic creation, metadata, produce/fetch, group join/leave/heartbeat, per-member offset acknowledgements, acknowledgement and inactivity expiry | Partition assignment/rebalance, durable metadata/offsets, replication |
| Storage | CAS-published in-memory batches and immutable fetch snapshots with acknowledgement-based prefix reclamation and a shared retained-memory budget; default Chronicle Queue logs with continuous logical offsets, validated recovery, full-history reads, native persisted indexes/mapping, and bounded lock-free MPSC group commit | File retention, payload checksums, persisted Setu logical index, configurable file ack modes |
| Client | Synchronous plaintext and TLS/mTLS requests with timeouts/hostname verification; create, single and batched produce, fetch, group join/leave, heartbeat, acknowledgement, and metadata helpers | High-level producer/consumer APIs, retries, async I/O |
| Operations | Runnable local-development JAR with properties configuration, blocking/selector transport and persistent rolling operational logs; configurable connection cap/backlog and read/idle timeout; functional integration-test module, development certificate script, ordered CI stages, and progressive real-TCP capacity benchmark with per-stage throughput, p99 latency, CPU, and heap reports | Metrics endpoint, secure standalone deployment configuration, distributed load/failure testing |

Consumer membership and committed offsets remain process-local and disappear on restart. File mode uses Chronicle Queue daily rolls under the configured topic/partition directory, with a 64 MiB default mapping block configurable through `queue.block.bytes` or `DefaultBroker`. Existing `.log` data requires [offline migration](setu-storage/README.md#migrating-existing-data). File mode retains complete history and reads from offset zero. In-memory mode creates no data directory, starts new members at join-time offsets, and reclaims acknowledged prefixes while preserving monotonic logical offsets. `DefaultBroker` uses a 30-second acknowledgement deadline and a 60-second inactivity deadline by default in either mode; background maintenance expires inactive or unacknowledged members. See [setu-core/README.md](setu-core/README.md), [setu-storage/README.md](setu-storage/README.md), [setu-client/README.md](setu-client/README.md), [setu-server/README.md](setu-server/README.md), and [setu-security/README.md](setu-security/README.md) for module-level details.

The later numbered sections remain design goals unless the snapshot above or a module README explicitly marks them implemented.

## Integration tests and local certificates

Functional cross-module TCP and mTLS scenarios live in `setu-integration-tests`; the tests create temporary certificates with the JDK `keytool`. For local development certificates, run `bash ./scripts/generate-certs.sh` on macOS/Linux or `./scripts/generate-certs.ps1` in PowerShell (optional output directory; `SETU_CERT_PASSWORD` overrides the development password). Generated material is ignored under `certs/generated/` and must not be used in production. Chaos and capacity workloads are kept under `setu-benchmark/src/test/java/io/setu/benchmark/chaos`.

CI runs production unit tests, benchmark-helper tests, integration tests, bounded chaos and sustained smoke benchmarks. Two automatic node-target jobs collect three independent repetitions with JFR and publish the latest measurements and qualification status on the default branch. They use `--report-only` so a completed miss of the 500K/250K targets produces an unqualified badge and a warning; crashes, request errors and incomplete captures still fail. Explicit node-target commands enforce qualification by default. A manually dispatched full benchmark adds eight capacity jobs covering two storage modes and four payloads; its full 1 KiB jobs enforce the separate 200K/150K throughput gates. The legacy exhaustion ramp is available via `--kind capacity` for explicit diagnostics and does not feed sustained badges.

Dependency direction:

```text
                   setu-server
                       │
                       ├──────────────► setu-security
                       │
                       ▼
                   setu-core
                       │
                       ▼
                  setu-storage
```

The client communicates with the broker through the server protocol.

Security should be exposed as interfaces so that the core broker does not become tightly coupled to a particular authentication implementation.

---

# 32. Technology Stack

* **Java 26**
* **Maven**
* Java NIO
* Java Foreign Function & Memory API
* `MemorySegment`
* TCP
* TLS / mTLS
* X.509 certificates
* PostgreSQL
* Docker
* Kubernetes
* JUnit
* Benchmarking / profiling tools

---

# 33. Deployment

Initial local deployment:

```text
Setu
 │
 ├── PostgreSQL
 │
 └── Local filesystem
```

Containerized deployment:

```text
┌─────────────────────┐
│ Kubernetes / Docker │
│                     │
│ Setu Broker         │
│ PostgreSQL          │
│ Persistent Storage  │
│ TLS Secrets         │
└─────────────────────┘
```

File mode must use persistent storage for durable deployments. In-memory mode is ephemeral by design and suits workloads that prioritize fast message handling without retaining old traces.

---

# 34. Success Criteria

Setu is successful when it can demonstrate:

### Messaging

* High-throughput message ingestion
* Low-latency message consumption
* Concurrent producers and consumers
* Partition-level ordering
* Consumer groups
* Offset tracking

### Storage

* Persistent append-only logs
* FFM / `MemorySegment` based access
* Segment rotation
* Retention
* Crash recovery
* Partial-write recovery

### Reliability

* Bounded memory usage
* Backpressure
* Consumer recovery
* Producer failure handling
* Broker restart recovery

### Security

* TLS
* mTLS
* Authentication
* Topic-level authorization
* Administrative authorization
* Client quotas
* Rate limiting
* Secure certificate/credential lifecycle
* Protocol validation

### Performance

* Measured throughput
* Measured p50/p95/p99 latency
* Connection scalability
* Memory usage
* GC behavior
* Storage performance
* Security overhead

---

# 35. Core Principle

> **Build the transport and storage layer yourself — secure it, make it recoverable, then measure the trade-offs.**

---

# 36. Latest Build Progress

The generated CI badges and sustained tables at the top of this README are the source of published performance results. The new full profile has not been certified by the short local smoke checks. It uses one topic per producer/consumer pair so consumed records represent unique delivery, warmup at each concurrency, measured HDR percentiles, and bounded resource-aware concurrency. Historical burst and fanout results remain diagnostic only.

Memory-log operations use immutable chunk/index snapshots published with CAS; file group-commit admission and queue publication also use CAS. Memory fetches materialize outside the partition coordination lock, and memory producers skip wakeup locking when no long poll is registered. Membership bookkeeping, long-poll waits, file-fetch scheduling, and file I/O still require coordination. Local comparison results are recorded in [the consolidated benchmark history](setu-benchmark/README.md#earlier-measurements).
