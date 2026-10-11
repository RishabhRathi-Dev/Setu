# Setu

[CI workflow](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
<!-- benchmark-metrics-badges:start -->
[![In-Memory Capacity from latest benchmark](https://img.shields.io/badge/1%2C024%C3%971%2C024%20%C2%B7%20JVM%20OOM%20%C2%B7%20peak%20CPU%20100%25%20%C2%B7%20758%20MB%20%C2%B7%20p99%202.1s-red?label=in-memory%20capacity&cacheSeconds=300)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![In-Memory Peak Rates from latest benchmark](https://img.shields.io/badge/206.34K%20prod%2Fs%20%C2%B7%20981.66K%20consumed%2Fs-blue?label=in-memory%20peak%20rates&cacheSeconds=300)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![In-Memory Jfr from latest benchmark](https://img.shields.io/badge/stage%2011%20%C2%B7%201.46%20GiB%2Fs%20alloc%20%C2%B7%201.3ms%20GC-blue?label=in-memory%20JFR&cacheSeconds=300)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![File Capacity from latest benchmark](https://img.shields.io/badge/1%2C024%C3%971%2C024%20%C2%B7%20broker%2Fclient%20limit%20%C2%B7%20peak%20CPU%2094%25%20%C2%B7%20596%20MB%20%C2%B7%20p99%202.1s-yellow?label=file%20capacity&cacheSeconds=300)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![File Peak Rates from latest benchmark](https://img.shields.io/badge/123.41K%20prod%2Fs%20%C2%B7%20152.77K%20consumed%2Fs-blue?label=file%20peak%20rates&cacheSeconds=300)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
[![File Jfr from latest benchmark](https://img.shields.io/badge/stage%2011%20%C2%B7%202.14%20GiB%2Fs%20alloc%20%C2%B7%200.2ms%20GC-blue?label=file%20JFR&cacheSeconds=300)](https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml)
<!-- benchmark-metrics-badges:end -->

Benchmark badges show terminal capacity, peak CPU, heap, p99 latency, and peak producer/consumer rates for each storage mode. Their metric values are embedded directly in Shields URLs, so they render without access to repository-hosted JSON. For private repositories, the workflow status image is replaced by a link because GitHub workflow badges are not externally accessible; metric badges continue to update from successful runs on the default branch. The JSON snapshots are committed for traceability.

## Latest capacity benchmark details

The table is generated from every stage of the latest successful default-branch capacity run. CPU is process CPU load; p99 is the approximate request-latency histogram reported by the load worker.

<!-- benchmark-capacity-details:start -->
### Target progress

| Storage mode | Target produce rate | Peak observed | Target progress |
| --- | ---: | ---: | ---: |
| In-memory | 10,000,000 records/s | 206.34K records/s | 2.1% |
| File | 1,000,000 records/s | 123.41K records/s | 12.3% |

### Progressive capacity stages

| Mode | Stage | Producers | Consumers | Duration | Produced | Produce/s | Consumed | Consume/s | Avg CPU | Peak CPU | Heap | p99 request | Stage result |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| In-memory | 1 | 1 | 1 | 2.01s | 414,720 | 206.34K | 290,304 | 144.44K | 75.7% | 81.4% | 330.0 MB | 8.389 ms | Ramping |
| In-memory | 2 | 2 | 2 | 2.01s | 330,496 | 164.09K | 413,696 | 205.40K | 65.1% | 75.2% | 340.0 MB | 8.389 ms | Ramping |
| In-memory | 3 | 4 | 4 | 2.01s | 277,760 | 138.18K | 620,800 | 308.84K | 68.4% | 79.9% | 352.0 MB | 16.777 ms | Ramping |
| In-memory | 4 | 8 | 8 | 2.01s | 229,632 | 114.30K | 856,576 | 426.35K | 66.7% | 75.3% | 380.0 MB | 33.554 ms | Ramping |
| In-memory | 5 | 16 | 16 | 2.01s | 195,328 | 97.06K | 1,149,952 | 571.39K | 67.3% | 77.9% | 502.0 MB | 33.554 ms | Ramping |
| In-memory | 6 | 32 | 32 | 2.02s | 156,160 | 77.43K | 1,345,536 | 667.13K | 67.4% | 77.9% | 516.0 MB | 134.218 ms | Ramping |
| In-memory | 7 | 64 | 64 | 2.01s | 139,008 | 69.19K | 1,323,520 | 658.76K | 66.0% | 97.9% | 382.0 MB | 134.218 ms | Ramping |
| In-memory | 8 | 128 | 128 | 1.01s | 146,688 | 145.70K | 794,624 | 789.28K | 97.3% | 99.8% | 460.0 MB | 536.871 ms | cpu hold |
| In-memory | 9 | 256 | 256 | 1.51s | 158,464 | 105.11K | 1,234,176 | 818.67K | 96.9% | 99.7% | 438.0 MB | 1073.742 ms | cpu hold |
| In-memory | 10 | 512 | 512 | 1.51s | 225,536 | 149.45K | 763,904 | 506.19K | 92.1% | 99.0% | 768.0 MB | 536.871 ms | cpu hold |
| In-memory | 11 | 1,024 | 1,024 | 2.11s | 248,320 | 117.72K | 491,776 | 233.13K | 91.4% | 100.0% | 758.0 MB | 2147.484 ms | Ramping |
| File | 1 | 1 | 1 | 2.00s | 184,832 | 92.20K | 184,576 | 92.07K | 57.9% | 81.9% | 528.0 MB | 16.777 ms | Ramping |
| File | 2 | 2 | 2 | 2.00s | 224,768 | 112.15K | 306,176 | 152.77K | 51.4% | 61.9% | 494.0 MB | 33.554 ms | Ramping |
| File | 3 | 4 | 4 | 2.01s | 203,776 | 101.62K | 204,288 | 101.88K | 28.4% | 42.0% | 508.0 MB | 67.109 ms | Ramping |
| File | 4 | 8 | 8 | 2.00s | 163,072 | 81.34K | 104,960 | 52.35K | 19.2% | 31.9% | 310.0 MB | 134.218 ms | Ramping |
| File | 5 | 16 | 16 | 2.01s | 148,992 | 74.28K | 104,704 | 52.20K | 17.7% | 25.8% | 534.0 MB | 536.871 ms | Ramping |
| File | 6 | 32 | 32 | 2.00s | 247,296 | 123.41K | 121,344 | 60.56K | 23.2% | 33.9% | 506.0 MB | 536.871 ms | Ramping |
| File | 7 | 64 | 64 | 2.00s | 229,632 | 114.61K | 83,200 | 41.52K | 24.2% | 51.9% | 584.0 MB | 1073.742 ms | Ramping |
| File | 8 | 128 | 128 | 2.01s | 184,576 | 91.87K | 140,288 | 69.83K | 75.2% | 98.7% | 388.0 MB | 2147.484 ms | Ramping |
| File | 9 | 256 | 256 | 1.01s | 43,008 | 42.76K | 81,152 | 80.68K | 98.4% | 99.7% | 760.0 MB | 1073.742 ms | cpu hold |
| File | 10 | 512 | 512 | 2.01s | 31,744 | 15.76K | 111,360 | 55.28K | 92.1% | 95.7% | 768.0 MB | 4294.967 ms | Ramping |
| File | 11 | 1,024 | 1,024 | 0.53s | 0 | 0 | 0 | 0 | 94.0% | 94.0% | 596.0 MB | 2147.484 ms | broker/client limit |

### CPU saturation hold

| Mode | Stage | Requested hold | Observed hold | Produce/s | Consume/s | Avg CPU | Outcome |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| File | 9 | 10.00s | 10.06s | 84.30K | 19.31K | 97.7% | Completed |
| In-memory | 8 | 10.00s | 10.06s | 5.40K | 981.66K | 98.3% | Completed |
| In-memory | 9 | 10.00s | 10.04s | 0 | 938.78K | 98.5% | Completed |
| In-memory | 10 | 10.00s | 10.05s | 306 | 807.23K | 97.2% | Completed |

### JFR diagnostics

Allocation rates are estimates from JFR allocation samples within each measured stage; GC pause time is the overlap of recorded pauses with that stage. Warmup and stage cleanup are excluded. CPU samples cover broker and load clients together. Backpressure waits include producer pacing and memory-budget retries.

| Mode | Stage | Estimated allocation | GC pause | CPU samples | Hottest sampled method | Backpressure waits |
| --- | ---: | ---: | ---: | ---: | --- | ---: |
| File | 1 | 969.5 MiB/s | 0.11 ms | 37 | `io.setu.server.connection.FramedConnectionHandler.handle` | 0 |
| File | 2 | 1290.0 MiB/s | 0.21 ms | 35 | `io.setu.storage.log.FileMessageLog.appendValues` | 0 |
| File | 3 | 991.3 MiB/s | 0.11 ms | 14 | `io.setu.server.connection.FramedConnectionHandler.handle` | 0 |
| File | 4 | 674.7 MiB/s | 0.12 ms | 10 | `io.setu.storage.log.MessageValue.readFrom` | 0 |
| File | 5 | 557.8 MiB/s | 0.12 ms | 17 | `io.setu.storage.log.MessageValue.readFrom` | 0 |
| File | 6 | 979.9 MiB/s | 0.13 ms | 24 | `io.setu.storage.log.MessageValue.readFrom` | 0 |
| File | 7 | 788.6 MiB/s | 0.17 ms | 20 | `io.setu.storage.log.FileMessageLog.appendValues` | 0 |
| File | 8 | 1571.5 MiB/s | 0.90 ms | 75 | `io.setu.storage.log.MessageValue.readFrom` | 1,179 |
| File | 9 | 2921.3 MiB/s | 9.68 ms | 681 | `io.setu.storage.log.MessageValue.readFrom` | 24,645 |
| File | 10 | 2305.4 MiB/s | 1.31 ms | 105 | `io.setu.storage.log.MessageValue.readFrom` | 3,640 |
| File | 11 | 2192.7 MiB/s | 0.17 ms | 25 | `io.setu.core.protocol.ProtocolCodec.encodeRequest` | 775 |
| In-memory | 1 | 1321.6 MiB/s | 0.73 ms | 74 | `io.setu.storage.log.MessageValue.readFrom` | 147 |
| In-memory | 2 | 1318.0 MiB/s | 0.80 ms | 69 | `io.setu.core.protocol.FrameCodec.readFrame` | 744 |
| In-memory | 3 | 1568.9 MiB/s | 0.96 ms | 59 | `io.setu.core.protocol.ProtocolCodec.encodeFetchResponse` | 1,914 |
| In-memory | 4 | 1852.7 MiB/s | 0.91 ms | 88 | `io.setu.storage.log.MessageValue.readFrom` | 3,397 |
| In-memory | 5 | 2225.9 MiB/s | 0.89 ms | 70 | `io.setu.core.protocol.ProtocolCodec.encodeFetchResponse` | 7,853 |
| In-memory | 6 | 2470.6 MiB/s | 0.78 ms | 83 | `io.setu.storage.log.MessageValue.readFrom` | 2,416 |
| In-memory | 7 | 2486.6 MiB/s | 0.88 ms | 80 | `io.setu.core.protocol.ProtocolCodec.decodeResponse` | 2,313 |
| In-memory | 8 | 3217.9 MiB/s | 8.50 ms | 637 | `io.setu.core.protocol.ProtocolCodec.encodeFetchResponse` | 32,979 |
| In-memory | 9 | 3074.5 MiB/s | 9.42 ms | 679 | `io.setu.core.protocol.ProtocolCodec.encodeFetchResponse` | 47,327 |
| In-memory | 10 | 2598.7 MiB/s | 8.04 ms | 578 | `io.setu.core.protocol.ProtocolCodec.decodeResponse` | 62,103 |
| In-memory | 11 | 1499.7 MiB/s | 1.25 ms | 90 | `io.setu.storage.log.MessageValue.readFrom` | 23,623 |

JFR recordings and raw captures are retained in the CI benchmark-diagnostics artifact. A resource crash may leave only the last completed stage checkpoint.

### Measured profile

- File: 1024-byte records, 256-record batches, 2000 ms stages, minimum 8 stages, memory-log budget 268435456 bytes, JFR true, CPU stop false, collector ZGC, string deduplication true.
- In-memory: 1024-byte records, 256-record batches, 2000 ms stages, minimum 8 stages, memory-log budget 268435456 bytes, JFR true, CPU stop false, collector ZGC, string deduplication true.

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

The current throughput objectives are **10 million records/s in memory** and **1 million records/s with file storage**. The standard benchmark and AOT-training payload is now **1,024 bytes**; the progressive profile keeps 256-record batches. These goals are observations to work toward, not pass/fail thresholds, and depend on the host. The generated measured-profile section records the payload size used by each published run; older 512-byte results are not directly comparable. A local batching experiment exceeded the file-ingestion target with 4,096-record batches over a 12-second stage, while consumers accumulated backlog; the memory target remains open.

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

The generated CI badges and capacity tables at the top of this README are the source of published performance results. Successful default-branch runs refresh them from the default progressive capture; local measurements and the separate large-batch experiment do not replace them.

The CI profile enables JFR for both storage modes, requires stages 1–8 (through 128 producers and 128 consumers), and continues through CPU saturation until a real runtime boundary. Its memory backlog budget and stage cleanup prevent earlier stages from exhausting the heap before connection scaling is measured. Per-stage allocation estimates, GC pauses, sampled hot methods, and backpressure waits are published with the rates; raw recordings remain in the benchmark-diagnostics artifact. The workflow validates the stage floor and JFR coverage before publishing any badge update.
