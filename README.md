# Setu

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

# 4. FFM / MemorySegment

Setu uses Java's **Foreign Function & Memory API (FFM)** for efficient interaction with file-backed and off-heap memory.

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

This provides a path for efficient:

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

Setu stores messages using append-only segmented files.

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

Segment rotation can be triggered by:

* Maximum segment size
* Maximum segment age
* Retention policy

Example:

```text
partition-0/

000000000000.log  ← sealed
000000000001.log  ← sealed
000000000002.log  ← active
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

The code currently provides a standalone `setu-security` library, but `setu-server` does not yet wire it into connection setup or request dispatch. The flows in this section describe the intended broker behavior; see `setu-security/README.md` for the implemented scope and outstanding security integration work.

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

# 29. Project Structure

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
│   └── src/
│       ├── main/java/
│       │   └── io/setu/benchmark/
│       │       ├── throughput/
│       │       ├── latency/
│       │       ├── concurrency/
│       │       ├── storage/
│       │       ├── security/
│       │       └── recovery/
│       │
│       └── resources/
│
├── config/
│   └── setu.yaml
│
├── certs/
│   └── .gitkeep
│
├── scripts/
│   ├── generate-certs.sh
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
| `setu-core`      | Broker semantics, topics, partitions, producers, consumers, groups |
| `setu-storage`   | Log segments, FFM, persistence, indexes, recovery, retention       |
| `setu-security`  | TLS, mTLS, authentication, authorization, quotas                   |
| `setu-server`    | TCP server, connections, framing, request dispatch                 |
| `setu-client`    | Producer and consumer client APIs                                  |
| `setu-benchmark` | Performance, scalability, security and failure benchmarks          |

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

# 31. Technology Stack

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

# 32. Deployment

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

The message log must use persistent storage rather than ephemeral container storage.

---

# 33. Success Criteria

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

# 34. Core Principle

> **Build the transport and storage layer yourself — secure it, make it recoverable, then measure the trade-offs.**
