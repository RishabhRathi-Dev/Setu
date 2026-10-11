# Setu

[CI workflow](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
<!-- benchmark-metrics-badges:start -->
[![In-Memory Capacity from latest benchmark](https://img.shields.io/badge/2%2C048%C3%972%2C048%20%C2%B7%20JVM%20OOM%20%C2%B7%20peak%20CPU%2097%25%20%C2%B7%20732%20MB%20%C2%B7%20p99%201.1s-red?label=in-memory%20capacity&cacheSeconds=300)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![In-Memory Peak Rates from latest benchmark](https://img.shields.io/badge/358.58K%20prod%2Fs%20%C2%B7%201.49M%20consumed%2Fs-blue?label=in-memory%20peak%20rates&cacheSeconds=300)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![In-Memory Jfr from latest benchmark](https://img.shields.io/badge/stage%2011%20%C2%B7%201.73%20GiB%2Fs%20alloc%20%C2%B7%202182.2ms%20GC-blue?label=in-memory%20JFR&cacheSeconds=300)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![File Capacity from latest benchmark](https://img.shields.io/badge/256%C3%97256%20%C2%B7%20broker%2Fclient%20limit%20%C2%B7%20peak%20CPU%200%25%20%C2%B7%200%20MB%20%C2%B7%20p99%20537ms-yellow?label=file%20capacity&cacheSeconds=300)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![File Peak Rates from latest benchmark](https://img.shields.io/badge/415.89K%20prod%2Fs%20%C2%B7%20518.98K%20consumed%2Fs-blue?label=file%20peak%20rates&cacheSeconds=300)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![File Jfr from latest benchmark](https://img.shields.io/badge/stage%209%20%C2%B7%201.50%20GiB%2Fs%20alloc%20%C2%B7%2020.8ms%20GC-blue?label=file%20JFR&cacheSeconds=300)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
<!-- benchmark-metrics-badges:end -->

Benchmark badges show terminal capacity, peak CPU, heap, p99 latency, and peak producer/consumer rates for each storage mode. Their metric values are embedded directly in Shields URLs, so they render without access to repository-hosted JSON. For private repositories, the workflow status image is replaced by a link because GitHub workflow badges are not externally accessible; metric badges continue to update from successful runs on the default branch. The JSON snapshots are committed for traceability.

## Latest capacity benchmark details

The table is generated from every stage of the latest successful default-branch capacity run. CPU is process CPU load; p99 is the approximate request-latency histogram reported by the load worker.

<!-- benchmark-capacity-details:start -->
### Target progress

| Storage mode | Target produce rate | Peak observed | Target progress |
| --- | ---: | ---: | ---: |
| In-memory | 10,000,000 records/s | 358.58K records/s | 3.6% |
| File | 1,000,000 records/s | 415.89K records/s | 41.6% |

### Progressive capacity stages

| Mode | Stage | Producers | Consumers | Duration | Produced | Produce/s | Consumed | Consume/s | Avg CPU | Peak CPU | Heap | p99 request | Stage result |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| In-memory | 1 | 1 | 1 | 2.01s | 637,696 | 317.96K | 636,928 | 317.58K | 98.5% | 99.9% | 68.8 MB | 4.194 ms | Ramping |
| In-memory | 2 | 2 | 2 | 2.01s | 721,664 | 358.58K | 972,288 | 483.11K | 97.8% | 99.9% | 194.0 MB | 16.777 ms | Ramping |
| In-memory | 3 | 4 | 4 | 2.01s | 654,592 | 325.97K | 1,692,672 | 842.90K | 97.7% | 99.9% | 376.0 MB | 16.777 ms | Ramping |
| In-memory | 4 | 8 | 8 | 2.02s | 473,344 | 234.55K | 1,948,160 | 965.36K | 98.2% | 99.9% | 222.0 MB | 16.777 ms | Ramping |
| In-memory | 5 | 16 | 16 | 2.01s | 391,168 | 194.54K | 2,466,816 | 1.23M | 98.1% | 99.9% | 277.0 MB | 33.554 ms | Ramping |
| In-memory | 6 | 32 | 32 | 2.01s | 280,576 | 139.51K | 2,499,584 | 1.24M | 98.1% | 99.7% | 236.0 MB | 134.218 ms | Ramping |
| In-memory | 7 | 64 | 64 | 2.01s | 258,048 | 128.51K | 2,468,864 | 1.23M | 98.1% | 99.6% | 302.0 MB | 268.435 ms | Ramping |
| In-memory | 8 | 128 | 128 | 1.00s | 251,136 | 250.15K | 1,026,560 | 1.02M | 97.1% | 99.8% | 294.2 MB | 536.871 ms | cpu hold |
| In-memory | 9 | 256 | 256 | 1.02s | 267,008 | 261.36K | 906,752 | 887.59K | 96.9% | 98.5% | 426.0 MB | 536.871 ms | cpu hold |
| In-memory | 10 | 512 | 512 | 1.02s | 311,296 | 304.60K | 510,976 | 499.98K | 96.8% | 98.9% | 582.5 MB | 1073.742 ms | cpu hold |
| In-memory | 11 | 1,024 | 1,024 | 1.02s | 300,288 | 293.74K | 54,272 | 53.09K | 95.8% | 99.9% | 501.1 MB | 1073.742 ms | cpu hold |
| In-memory | 12 | 2,048 | 2,048 | 1.14s | 264,192 | 231.19K | 0 | 0 | 96.2% | 97.3% | 731.5 MB | 1073.742 ms | cpu hold |
| File | 1 | 1 | 1 | 2.01s | 249,856 | 124.39K | 249,856 | 124.39K | 88.7% | 93.6% | 54.8 MB | 8.389 ms | Ramping |
| File | 2 | 2 | 2 | 2.01s | 366,592 | 182.63K | 731,648 | 364.49K | 88.5% | 91.9% | 51.2 MB | 8.389 ms | Ramping |
| File | 3 | 4 | 4 | 2.01s | 632,064 | 314.33K | 1,002,496 | 498.55K | 80.4% | 81.9% | 104.3 MB | 16.777 ms | Ramping |
| File | 4 | 8 | 8 | 2.01s | 610,816 | 304.10K | 1,042,432 | 518.98K | 87.4% | 89.9% | 67.7 MB | 33.554 ms | Ramping |
| File | 5 | 16 | 16 | 2.01s | 793,856 | 394.81K | 1,011,200 | 502.90K | 88.6% | 91.7% | 191.1 MB | 67.109 ms | Ramping |
| File | 6 | 32 | 32 | 2.02s | 748,800 | 371.57K | 1,011,712 | 502.03K | 90.4% | 97.5% | 206.9 MB | 134.218 ms | Ramping |
| File | 7 | 64 | 64 | 2.01s | 813,312 | 404.56K | 882,176 | 438.82K | 86.1% | 95.2% | 260.4 MB | 268.435 ms | Ramping |
| File | 8 | 128 | 128 | 2.02s | 840,960 | 415.89K | 807,936 | 399.56K | 87.1% | 95.4% | 416.4 MB | 536.871 ms | Ramping |
| File | 9 | 256 | 256 | 0.26s | 62,208 | 243.33K | 60,928 | 238.32K | 0.0% | 0.0% | 0.0 MB | 536.871 ms | broker/client limit |

### CPU saturation hold

| Mode | Stage | Requested hold | Observed hold | Produce/s | Consume/s | Avg CPU | Outcome |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| In-memory | 8 | 10.00s | 10.05s | 5.58K | 1.42M | 97.8% | Completed |
| In-memory | 9 | 10.00s | 10.06s | 0 | 1.49M | 98.4% | Completed |
| In-memory | 10 | 10.00s | 10.07s | 229 | 1.35M | 98.3% | Completed |
| In-memory | 11 | 10.00s | 10.19s | 4.65K | 1.06M | 96.8% | Completed |
| In-memory | 12 | 10.00s | 0.26s | 132.42K | 0 | 95.8% | broker/client limit |

### JFR diagnostics

Allocation rates are estimates from JFR allocation samples within each measured stage; GC pause time is the overlap of recorded pauses with that stage. Warmup and stage cleanup are excluded. CPU samples cover broker and load clients together. Backpressure waits include producer pacing and memory-budget retries.

| Mode | Stage | Estimated allocation | GC pause | CPU samples | Hottest sampled method | Backpressure waits |
| --- | ---: | ---: | ---: | ---: | --- | ---: |
| File | 1 | 654.3 MiB/s | 26.49 ms | 60 | `io.setu.storage.log.GroupCommitMessageLog.writeLoop` | 0 |
| File | 2 | 1507.4 MiB/s | 48.88 ms | 53 | `io.setu.core.protocol.ProtocolCodec.decodeResponse` | 0 |
| File | 3 | 2174.8 MiB/s | 115.01 ms | 64 | `io.setu.core.protocol.ProtocolCodec.decodeResponse` | 0 |
| File | 4 | 2198.4 MiB/s | 179.87 ms | 80 | `io.setu.core.protocol.ProtocolCodec.decodeResponse` | 0 |
| File | 5 | 2363.4 MiB/s | 193.61 ms | 86 | `io.setu.core.protocol.ProtocolCodec.decodeResponse` | 0 |
| File | 6 | 2314.2 MiB/s | 236.12 ms | 81 | `io.setu.server.connection.FramedConnectionHandler.handle` | 0 |
| File | 7 | 2344.3 MiB/s | 237.90 ms | 81 | `io.setu.core.protocol.ProtocolCodec.decodeResponse` | 0 |
| File | 8 | 2237.4 MiB/s | 288.08 ms | 64 | `io.setu.storage.log.FileMessageLog.appendValues` | 0 |
| File | 9 | 1540.3 MiB/s | 20.79 ms | 8 | `io.setu.storage.log.FileMessageLog.appendValues` | 0 |
| In-memory | 1 | 1247.7 MiB/s | 35.23 ms | 64 | `io.setu.server.connection.FramedConnectionHandler.handle` | 0 |
| In-memory | 2 | 1554.3 MiB/s | 161.20 ms | 76 | `io.setu.core.protocol.ProtocolCodec.decodeResponse` | 345 |
| In-memory | 3 | 2130.9 MiB/s | 154.57 ms | 93 | `io.setu.core.protocol.ProtocolCodec.decodeResponse` | 1,581 |
| In-memory | 4 | 2127.7 MiB/s | 140.47 ms | 118 | `io.setu.core.protocol.ProtocolCodec.decodeResponse` | 3,189 |
| In-memory | 5 | 2500.6 MiB/s | 128.21 ms | 116 | `io.setu.core.protocol.ProtocolCodec.decodeResponse` | 4,092 |
| In-memory | 6 | 2435.4 MiB/s | 121.41 ms | 108 | `io.setu.core.protocol.ProtocolCodec.decodeResponse` | 734 |
| In-memory | 7 | 2383.3 MiB/s | 133.35 ms | 111 | `io.setu.core.protocol.ProtocolCodec.decodeResponse` | 1,473 |
| In-memory | 8 | 2413.6 MiB/s | 651.65 ms | 755 | `io.setu.core.protocol.ProtocolCodec.encodeFetchResponse` | 28,970 |
| In-memory | 9 | 2502.2 MiB/s | 755.09 ms | 772 | `io.setu.core.protocol.ProtocolCodec.decodeResponse` | 45,473 |
| In-memory | 10 | 2276.1 MiB/s | 1091.32 ms | 662 | `io.setu.core.protocol.ProtocolCodec.decodeResponse` | 72,786 |
| In-memory | 11 | 1774.6 MiB/s | 2182.18 ms | 551 | `io.setu.core.protocol.ProtocolCodec.decodeResponse` | 122,300 |

JFR recordings and raw captures are retained in the CI benchmark-diagnostics artifact. A resource crash may leave only the last completed stage checkpoint.

### Measured profile

- File: 512-byte records, 256-record batches, 2000 ms stages, minimum 8 stages, memory-log budget 268435456 bytes, JFR true, CPU stop false.
- In-memory: 512-byte records, 256-record batches, 2000 ms stages, minimum 8 stages, memory-log budget 268435456 bytes, JFR true, CPU stop false.

### Capacity badge color legend

| Color | Meaning |
| --- | --- |
| 🔵 Blue | Peak producer/consumer rates; throughput values are observations, not pass/fail thresholds. |
| 🟠 Orange | Capacity ramp stopped after sustained CPU saturation. |
| 🔴 Red | Capacity ramp reached JVM heap exhaustion or an operating-system resource limit. |
| 🟡 Yellow | Capacity ramp stopped at a broker/client limit or client stall. |

File capacity stop: **broker/client limit**.

In-memory capacity stop: **JVM OOM**.
<!-- benchmark-capacity-details:end -->

**Setu** is a distributed message broker built from scratch in **Java 25**, inspired by systems such as Kafka.

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

The current throughput objectives are **10 million records/s in memory** and **1 million records/s with file storage**. They are measured as broker records over TCP, using 512-byte payloads in the progressive capacity workload; they are goals rather than pass/fail thresholds and depend on the host. Published default-profile runs remain below these targets. A local batching experiment exceeded the file-ingestion target with 4,096-record batches over a 12-second stage, while consumers accumulated backlog; the memory target remains open.

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

The root `pom.xml` is the Maven reactor for all modules. Use the checked-in Maven Wrapper from the repository root, with a Java 25 JDK installed:

```powershell
.\mvnw.cmd test
.\mvnw.cmd -pl setu-security test
.\mvnw.cmd -pl setu-integration-tests -am '-Dtest=*IntegrationTests' -Dsurefire.failIfNoSpecifiedTests=false test
.\mvnw.cmd -pl setu-benchmark -am '-Dtest=*ChaosTests,!ProgressiveCapacityChaosTests,QuickBrokerChaosBenchmarkTests' -Dsurefire.failIfNoSpecifiedTests=false test
.\mvnw.cmd -pl setu-benchmark -am '-Dtest=ProgressiveCapacityChaosTests' -Dsurefire.failIfNoSpecifiedTests=false test
```

On macOS/Linux, use `bash ./mvnw test` (or `./mvnw test` when the checkout marks the script executable).

The first invocation downloads the pinned Maven distribution. The wrapper supplies Maven; it does not install Java.

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

# 9. Persistent Log (target design)

The target design stores messages using append-only segmented files. File mode now writes one or more length-prefixed `.log` segments per partition and rotates at a configurable size (1 GiB by default). Existing one-file partitions remain readable as the first segment.

Example:

```text
data/
└── orders/
    ├── partition-0/
    │   ├── 000000000000.log  ← starts at offset 0
    │   ├── 000000000500.log  ← starts at offset 500
    │   └── 000000001000.log  ← starts at offset 1,000
    │
    └── partition-1/
        ├── 000000000000.log
        └── 000000000001.log
```

Each segment contains sequential records.

```text
┌───────────────────────────────────────┐
│ Segment                               │
│                                       │
│ offset 0   → record                   │
│ offset 1   → record                   │
│ offset 2   → record                   │
│ offset 3   → record                   │
│ ...                                   │
└───────────────────────────────────────┘
```

---

# 10. Segment Lifecycle

Each partition has an active segment.

```text
             ┌──────────────┐
             │    ACTIVE    │
             └──────┬───────┘
                    │
                 rotate
                    │
                    ▼
             ┌──────────────┐
             │    SEALED    │
             └──────┬───────┘
                    │
                    ▼
             Retained / Expired
```

The current file backend rotates on maximum segment size. Age-based rotation and retention remain future work:

* Maximum segment age
* Retention policy

Example:

```text
partition-0/

000000000000.log  ← starts at offset 0
000000000500.log  ← starts at offset 500
000000001000.log  ← active, starts at offset 1,000
```

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
| `setu-storage`   | Current: in-memory logs and file-backed partition logs with configurable 1 GiB segments, shared logical-offset APIs, and startup recovery. Target: FFM, persisted indexes, retention |
| `setu-security`  | Current: TLS configuration/authentication, ACL interfaces and in-memory authorizer, quotas |
| `setu-server`    | Current: TCP/TLS connections, framing, request authorization, dispatch, plaintext selector transport |
| `setu-client`    | Current: synchronous protocol v1 socket client. Target: producer/consumer APIs |
| `setu-benchmark` | Real-TCP throughput, recovery, expiry, malformed-traffic, concurrency, and progressive capacity exhaustion tests in `src/test/java/io/setu/benchmark/chaos`; doubles producer and consumer counts until CPU saturation, JVM OOM, or a host resource limit |
| `setu-integration-tests` | Functional cross-module TCP and mTLS requirement tests for both storage modes; no saturation or chaos workloads |

# 31. Current Implementation Snapshot

This section distinguishes the current integrated implementation from the broader goals above. The architecture remains layered: the server owns transport and request processing, `setu-security` owns reusable security primitives, `setu-core` owns broker/protocol APIs, and `setu-storage` owns partition-log implementations. The broker selects in-memory or local file storage; message payloads are not stored in PostgreSQL.

| Area | Implemented in this checkout | Not implemented yet |
| --- | --- | --- |
| Protocol and transport | Version 1 commands, framed TCP, strict request decoding, correlation IDs, structured errors, single and batched produce, optional member-aware fetch/ack fields with legacy-body support, TLS/mTLS blocking transport, plaintext selector multiplexing | Selector TLS, asynchronous/pipelined client API, negotiated protocol compatibility |
| Security | mTLS identity, command/resource authorization mapping, exact default-deny in-memory ACLs, per-principal token-bucket quota checks | Persistent/cluster ACLs, wildcard policy, credential issuance/rotation, distributed quotas |
| Broker | Topic creation, metadata, produce/fetch, group join/leave/heartbeat, per-member offset acknowledgements, acknowledgement and inactivity expiry | Partition assignment/rebalance, durable metadata/offsets, replication |
| Storage | In-memory logs with acknowledgement-based prefix reclamation and a shared configurable retained-memory budget that rejects overflowing appends; segmented file logs with configurable size, continuous logical offsets, tail recovery, and full-history reads | FFM, file retention, checksums, persisted indexes, configurable file ack modes, protocol-buffer admission and file-persistence backpressure |
| Client | Synchronous plaintext and TLS/mTLS requests with timeouts/hostname verification; create, single and batched produce, fetch, group join/leave, heartbeat, acknowledgement, and metadata helpers | High-level producer/consumer APIs, retries, async I/O |
| Operations | Runnable local-development JAR with properties configuration, blocking/selector transport and persistent rolling operational logs; configurable connection cap/backlog and read/idle timeout; functional integration-test module, development certificate script, ordered CI stages, and progressive real-TCP capacity benchmark with per-stage throughput, p99 latency, CPU, and heap reports | Metrics endpoint, secure standalone deployment configuration, distributed load/failure testing |

Consumer membership and committed offsets remain process-local and disappear on restart. File mode stores topic/partition logs as segments named by base offset under the configured data directory; the default segment size is 1 GiB and can be configured through `DefaultBroker`. Existing single-file partitions remain readable as the first segment. File mode retains complete history and reads from offset zero. In-memory mode creates no data directory, starts new members at join-time offsets, and reclaims acknowledged prefixes while preserving monotonic logical offsets. `DefaultBroker` uses a 30-second acknowledgement deadline and a 60-second inactivity deadline by default in either mode; expired members are removed during broker request processing. See [setu-core/README.md](setu-core/README.md), [setu-storage/README.md](setu-storage/README.md), [setu-client/README.md](setu-client/README.md), [setu-server/README.md](setu-server/README.md), and [setu-security/README.md](setu-security/README.md) for module-level details.

The later numbered sections remain design goals unless the snapshot above or a module README explicitly marks them implemented.

## Integration tests and local certificates

Functional cross-module TCP and mTLS scenarios live in `setu-integration-tests`; the tests create temporary certificates with the JDK `keytool`. For local development certificates, run `bash ./scripts/generate-certs.sh` on macOS/Linux or `./scripts/generate-certs.ps1` in PowerShell (optional output directory; `SETU_CERT_PASSWORD` overrides the development password). Generated material is ignored under `certs/generated/` and must not be used in production. Chaos and capacity workloads are kept under `setu-benchmark/src/test/java/io/setu/benchmark/chaos`.

CI runs three ordered stages: production unit tests, all integration test classes, then all benchmark chaos tests. The bounded TCP chaos workload measures 20,000 records with sixteen producers and sixteen consumers in each storage mode; producers send 256 records per batch. The broker-limits workload also drives sixteen producers and sixteen consumers through 16,000 records. These cases exercise batch offset assignment, acknowledgement expiry, fetch paging, file-segment rotation, and broker restart. Other chaos cases cover record-size limits, truncated TCP frames, concurrent consumer progress, and forced broker recovery. Bounded workload deadlines are five minutes, while the progressive capacity test runs last in its own Maven invocation. It launches one heap-capped child JVM per storage mode, doubles producer and consumer counts at each stage, and reports records per second, approximate p99 request latency, process CPU, and heap until sustained CPU saturation, JVM OOM, or an OS resource limit. Its output is diagnostic and does not apply machine-sensitive throughput thresholds. The CI badges above track the full ordered workflow, including the capacity stage; the observed-capacity badge summarizes the latest local run.

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

* **Java 25**
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

The generated CI badges and capacity tables at the top of this README are the source of published performance results. Successful default-branch runs refresh them from the default progressive capture; local measurements and the separate large-batch experiment do not replace them.

The CI profile enables JFR for both storage modes, requires stages 1–8 (through 128 producers and 128 consumers), and continues through CPU saturation until a real runtime boundary. Its memory backlog budget and stage cleanup prevent earlier stages from exhausting the heap before connection scaling is measured. Per-stage allocation estimates, GC pauses, sampled hot methods, and backpressure waits are published with the rates; raw recordings remain in the benchmark-diagnostics artifact. The workflow validates the stage floor and JFR coverage before publishing any badge update.
