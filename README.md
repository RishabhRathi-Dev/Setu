# Setu

**Setu** is a Java 25 distributed message broker inspired by Kafka.

The goal is to build a high-throughput messaging system from the ground up, focusing on **networking, concurrency, persistent logs, memory efficiency, and failure recovery**.

## Core Architecture

```text
Producer
   │
   ▼
Setu Broker
   │
   ├──► FFM / MemorySegment
   │         │
   │         ▼
   │    Append-only Log
   │         │
   │         ▼
   │      Consumers
   │
   └──► PostgreSQL
        (metadata only)
```

* **FFM / MemorySegment** — efficient interaction with file-backed log segments
* **Append-only files** — durable message storage
* **PostgreSQL** — broker metadata, topics, partitions, consumer groups, offsets
* **Memory-mapped log access** — low-latency reads for consumers

## Goals

* TCP-based producer/consumer protocol
* Topics and partitions
* Persistent append-only segmented logs
* FFM-based file/memory access
* Consumer groups and offsets
* Concurrent producers and consumers
* Batching and backpressure
* Configurable acknowledgement semantics
* Crash recovery and log recovery
* Message retention and segment rotation
* Metrics and performance benchmarking

## Tech

* Java 25
* Maven
* Java NIO
* Foreign Function & Memory API
* PostgreSQL
* Docker
* Kubernetes — later

## Success Criteria

Setu should demonstrate:

* High concurrent connection handling
* High-throughput message ingestion
* Low-latency consumer reads
* Durable messages across broker restarts
* Correct recovery from partial/crashed writes
* Backpressure under slow persistence
* Measured throughput, p95/p99 latency, memory usage, and GC behavior

### Core Idea

> **Build the transport and storage layer yourself — then measure the trade-offs.**
