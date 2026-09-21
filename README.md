# Setu

**Setu** is a distributed message broker built from scratch in **Java 25**, inspired by systems such as Kafka.

The project focuses on understanding and implementing the underlying engineering problems behind high-throughput messaging: networking, concurrency, memory management, persistence, partitioning, consumer coordination, failure recovery, and performance.

Setu is intentionally designed as a systems project rather than a CRUD application.

---

## 1. Goals

Setu aims to provide:

* High-throughput message ingestion
* High concurrent connection handling
* Low-latency message consumption
* Partitioned message streams
* Concurrent producers and consumers
* Consumer groups and offsets
* Persistent append-only logs
* Efficient off-heap / memory-mapped access using the **Foreign Function & Memory API (FFM)**
* Crash and partial-write recovery
* Backpressure under slow persistence
* Configurable acknowledgement semantics
* Segment rotation and retention
* Performance benchmarking
* Observable system behavior

The primary goal is to understand the trade-offs between:

**networking → concurrency → memory → persistence → durability → throughput → latency**

---

# 2. High-Level Architecture

```text
                         ┌──────────────────┐
                         │     Producer     │
                         └────────┬─────────┘
                                  │
                                  │ TCP
                                  ▼
                         ┌──────────────────┐
                         │   Setu Server    │
                         │  Network Layer   │
                         └────────┬─────────┘
                                  │
                                  ▼
                         ┌──────────────────┐
                         │   Setu Core      │
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
                         │  Log Manager     │
                         │                  │
                         │ Offset → Segment │
                         │ Append / Fetch   │
                         │ Rotation         │
                         │ Recovery         │
                         └────────┬─────────┘
                                  │
                     ┌────────────┴────────────┐
                     │                         │
                     ▼                         ▼
             ┌──────────────┐          ┌──────────────┐
             │ Memory / FFM  │          │ Segment File │
             │ MemorySegment │◄────────►│   Log Store  │
             └──────────────┘          └──────────────┘
                     │
                     ▼
                 Consumers

        ┌────────────────────────────────────┐
        │           PostgreSQL               │
        │                                    │
        │ Topics                             │
        │ Partitions                         │
        │ Consumer Groups                    │
        │ Consumer Offsets                   │
        │ Broker Metadata                    │
        └────────────────────────────────────┘
```

### Important architectural distinction

**PostgreSQL does not store message payloads.**

PostgreSQL is the **control plane / metadata store**.

The messaging data path is handled by Setu's log subsystem.

The intended storage model is:

```text
Producer
   │
   ▼
Setu Broker
   │
   ▼
In-memory active region
   │
   │ FFM / MemorySegment
   ▼
Append-only segment
   │
   ▼
Durable log file
```

FFM is the mechanism used to efficiently work with the memory/file-backed representation. It is **not the persistence layer itself**.

The log remains the durable source of message data.

---

# 3. Core Concepts

## Topics

A topic represents a logical stream of messages.

```text
orders
payments
notifications
events
```

A topic can contain multiple partitions.

---

## Partitions

Partitions provide:

* Parallelism
* Ordering within a partition
* Independent append streams
* Distribution of workload

Example:

```text
orders

partition-0
partition-1
partition-2
partition-3
```

Ordering is guaranteed within a partition rather than globally across a topic.

---

## Message Offset

Each message receives a monotonically increasing offset within its partition.

```text
partition-0

offset 100
offset 101
offset 102
offset 103
```

Consumers use offsets to track their position.

---

# 4. Message Lifecycle

A simplified producer flow:

```text
Producer
   │
   │ SEND(topic, partition, payload)
   ▼
Network Layer
   │
   ▼
Broker
   │
   ▼
Partition
   │
   ▼
Append / Memory Region
   │
   ▼
FFM MemorySegment
   │
   ▼
Persistent Segment
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
Broker
   │
   ▼
Log Manager
   │
   ├── Find segment
   ├── Resolve offset
   └── Read message
   │
   ▼
MemorySegment / File-backed region
   │
   ▼
Consumer
```

The consumer should **not know how segments or MemorySegments work**.

It asks the broker for a message at a logical offset.

---

# 5. Storage Architecture

Setu uses segmented append-only logs.

Example:

```text
data/
└── orders/
    ├── partition-0/
    │   ├── 000000000000.log
    │   ├── 000000000001.log
    │   └── 000000000002.log
    │
    └── partition-1/
        ├── 000000000000.log
        └── 000000000001.log
```

Each segment contains sequential messages.

```text
┌─────────────────────────────────────┐
│ Segment                              │
│                                     │
│ offset 0    → message               │
│ offset 1    → message               │
│ offset 2    → message               │
│ offset 3    → message               │
│ ...                                 │
└─────────────────────────────────────┘
```

Segments are rotated according to configurable conditions such as:

* Maximum size
* Maximum age
* Retention policy

---

# 6. Active and Sealed Segments

A partition maintains an active segment.

```text
Partition

Active
  │
  ├── append
  ├── append
  └── append

Sealed
  │
  ├── segment-001
  ├── segment-002
  └── segment-003
```

The active region is optimized for writes.

Once a segment reaches its configured limit:

```text
ACTIVE
   │
   ▼
SEAL
   │
   ▼
SEALED
   │
   ▼
PERSIST / RETAIN
```

This provides a bounded working set while preserving durable message history.

---

# 7. FFM / MemorySegment

Setu uses Java's **Foreign Function & Memory API** to work with off-heap and file-backed memory efficiently.

The intention is not to use FFM simply because it is a modern Java API.

It solves concrete problems around:

* Large message buffers
* Off-heap memory
* Memory-mapped files
* Reducing unnecessary heap copies
* Efficient sequential access
* Explicit memory lifetime management

Conceptually:

```text
Java Heap
   │
   │ metadata / control structures
   ▼

FFM MemorySegment
   │
   ▼

File-backed / mapped log region
   │
   ▼

Segment File
```

The benchmark suite should compare different storage/access strategies and measure whether the FFM implementation actually provides useful benefits.

---

# 8. PostgreSQL

PostgreSQL is deliberately limited to metadata.

Potential tables:

```text
topics
partitions
consumer_groups
consumer_offsets
brokers
segment_metadata
```

Example:

```text
consumer_offsets

group_id
topic
partition
offset
updated_at
```

Message payloads should not be stored in PostgreSQL.

This separation keeps the hot messaging path independent from relational database throughput.

---

# 9. Consumer Groups

A consumer group allows multiple consumers to process partitions cooperatively.

```text
Topic: orders

Partition 0 ─────► Consumer A
Partition 1 ─────► Consumer B
Partition 2 ─────► Consumer C
Partition 3 ─────► Consumer A
```

The system needs to handle:

* Consumer registration
* Partition assignment
* Offset tracking
* Consumer departure
* Rebalancing
* Duplicate processing
* Consumer recovery

---

# 10. Acknowledgement Semantics

Setu should distinguish between acknowledgement and actual durability.

Possible semantics:

```text
ACK_AFTER_APPEND
ACK_AFTER_PERSIST
```

For a durable acknowledgement, Setu must define exactly what "durable" means.

Writing data to the operating-system page cache is not equivalent to guaranteeing persistence after a machine failure.

The implementation should therefore make the durability boundary explicit, potentially using file synchronization mechanisms where required.

---

# 11. Backpressure

A high-throughput producer must not be allowed to grow memory without bounds.

Example:

```text
Producer rate
      │
      ▼
   100 MB/s

Persistence
      │
      ▼
    60 MB/s
```

Without backpressure:

```text
Memory
  ↑
  ↑
  ↑
  ↑
OOM
```

Setu should instead implement bounded buffering:

```text
Producer
   │
   ▼
Bounded Buffer
   │
   ├── capacity available → accept
   │
   └── capacity exhausted → block / reject / throttle
```

Backpressure behavior should be configurable and measurable.

---

# 12. Crash Recovery

Setu must be able to restart without corrupting the logical message stream.

Recovery involves:

```text
Broker restart
      │
      ▼
Discover segments
      │
      ▼
Read segment metadata
      │
      ▼
Identify active segment
      │
      ▼
Validate records
      │
      ▼
Detect incomplete tail
      │
      ▼
Recover valid records
      │
      ▼
Resume append
```

Potential failure cases:

* Process crash during append
* Partial record
* Truncated segment
* Broker restart
* Interrupted segment rotation
* Corrupted metadata
* Consumer restart

---

# 13. Network Protocol

The initial implementation should use TCP.

Conceptually:

```text
┌─────────────┐
│ TCP Server  │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ Frame Codec │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ Command     │
│ Dispatcher  │
└──────┬──────┘
       │
       ▼
 Setu Core
```

Possible commands:

```text
PRODUCE
FETCH
COMMIT_OFFSET
JOIN_GROUP
LEAVE_GROUP
HEARTBEAT
METADATA
```

The protocol should use explicit framing rather than relying on TCP packet boundaries.

---

# 14. Concurrency Model

Important concurrent components include:

* Network connections
* Producers
* Consumers
* Partition appenders
* Segment rotation
* Background persistence
* Consumer-group coordination
* Offset commits
* Recovery

The implementation should explicitly document synchronization decisions.

Potential primitives:

* `ExecutorService`
* Virtual threads where appropriate
* `ReentrantLock`
* `StampedLock`
* `Atomic*`
* Concurrent collections
* Lock-free structures where justified
* Bounded queues

The objective is not to eliminate locks everywhere.

The objective is to understand **where contention exists and why a particular concurrency strategy is appropriate**.

---

# 15. Observability

Setu should expose metrics such as:

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
append failures
consumer lag
GC activity
memory usage
```

This is important because the project's claims should come from benchmarks rather than theoretical numbers.

---

# 16. Benchmarking

The benchmark module should test:

### Throughput

```text
messages/sec
MB/sec
```

### Latency

```text
p50
p95
p99
```

### Concurrency

```text
100 connections
1,000 connections
10,000 connections
```

### Storage

Compare:

```text
heap-based buffering
vs
FFM / MemorySegment
```

### Message sizes

```text
128 B
1 KB
10 KB
100 KB
```

### Failure

Measure recovery time after:

```text
broker crash
consumer crash
producer disconnect
partial append
```

---

# 17. Project Structure

```text
setu/
│
├── pom.xml
│
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
│       └── test/java/
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
│       └── test/java/
│
├── setu-benchmark/
│   ├── pom.xml
│   └── src/
│       ├── main/java/
│       │   └── io/setu/benchmark/
│       │       ├── throughput/
│       │       ├── latency/
│       │       ├── concurrency/
│       │       ├── storage/
│       │       └── recovery/
│       └── resources/
│
├── config/
│   └── setu.yaml
│
├── scripts/
│   ├── start-broker.sh
│   ├── stop-broker.sh
│   └── benchmark.sh
│
├── docker/
│   └── Dockerfile
│
└── data/
    └── .gitkeep
```

---

# 18. Module Responsibilities

| Module           | Responsibility                                                     |
| ---------------- | ------------------------------------------------------------------ |
| `setu-core`      | Broker semantics, topics, partitions, producers, consumers, groups |
| `setu-storage`   | Log segments, FFM, persistence, indexes, recovery                  |
| `setu-server`    | TCP server and network protocol                                    |
| `setu-client`    | Producer/consumer client APIs                                      |
| `setu-benchmark` | Performance and failure benchmarking                               |

The important dependency direction is:

```text
setu-server
     │
     ▼
setu-core
     │
     ▼
setu-storage
```

The consumer does not directly manipulate storage internals.

---

# 19. Technology Stack

* Java 25
* Maven
* Java NIO
* Foreign Function & Memory API
* TCP / Java networking
* PostgreSQL
* Docker
* Kubernetes
* JUnit
* Benchmarking tooling

---

# 20. Success Criteria

Setu is successful when it can demonstrate:

* High concurrent connection handling
* High-throughput message ingestion
* Low-latency consumption
* Partition-level ordering
* Persistent messages across broker restarts
* Correct consumer offset recovery
* Partial-write recovery
* Bounded memory usage
* Backpressure under slow persistence
* Reliable segment rotation
* Measured p95/p99 latency
* Measured throughput
* Meaningful FFM vs heap/access-path benchmarks

### Core Principle

> **Build the transport and storage layer yourself — then measure the trade-offs.**
