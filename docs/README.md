# Documentation

Start with the [project README](../README.md) for a runnable quickstart and supported behavior. These guides describe the current implementation; the module READMEs provide API and subsystem detail.

## Choose a path

| Goal | Reading path |
| --- | --- |
| Try Setu locally | [Quickstart](../README.md#quick-start) → [runnable examples](../examples/README.md) |
| Add the Java client | [Maven dependency and client API](../setu-client/README.md) → [consumer semantics](../setu-core/README.md#broker-semantics) |
| Run or recover a broker | [Configuration](configuration.md) → [operations](operations.md) → [storage contracts](../setu-storage/README.md) |
| Embed a secure listener | [Secure server wiring](../setu-server/README.md#secure-broker-wiring-example) → [identities, ACLs, and quotas](../setu-security/README.md) |
| Change the implementation | [Contributing](../CONTRIBUTING.md) → [architecture](architecture.md) → [repository knowledge base](../AGENTS.md) |
| Investigate a problem | [Troubleshooting](operations.md#logs-and-diagnosis) → [support and issue reporting](../SUPPORT.md) |

## Project guides

| Guide | What it explains |
| --- | --- |
| [Architecture](architecture.md) | Module dependencies, request flow, persistence, consumers, and backpressure |
| [Java source layout](source-layout.md) | Type locations and migration from former nested public types |
| [Configuration](configuration.md) | Every standalone property, defaults, validation, and runtime controls |
| [Containers](containers.md) | Broker image builds/publication, CI-aligned limits, persistent Compose service, health checks and a local LGTM stack |
| [IntelliJ development](intellij.md) | Shared run/debug profiles, examples, JUnit suites, Maven builds, and debugger attachment |
| [Journal checkpoints](journal-checkpoints.md) | Compaction publication, fencing, recovery and upgrade compatibility |
| [Observability](observability.md) | Loopback readiness, broker/transport/JVM metrics, LGTM collection, logs and dispatch traces |
| [Operations](operations.md) | Packaging, startup, logs, shutdown, recovery, migration, and troubleshooting |
| [PostgreSQL outbox/inbox](postgres-outbox.md) | Transactional publishing intent, duplicate delivery and database effects |
| [Managed groups and transactions](transactions.md) | Partition ownership, producer sequencing, atomic consume/produce and acknowledgement modes |
| [Contributing](../CONTRIBUTING.md) | Build requirements, verification commands, and repository conventions |
| [Tooling](../scripts/README.md) | Launchers, certificate generation, benchmarks, JFR, and badge publication |
| [Security policy](../SECURITY.md) | Private vulnerability reporting and deployment boundaries |
| [Repository knowledge base](../AGENTS.md) | Source entry points, critical contracts, and verification guidance for coding agents |

## Module references

| Module | Reference |
| --- | --- |
| Core | [Broker semantics and protocol](../setu-core/README.md) |
| Storage | [Durability, ownership, recovery, and legacy migration](../setu-storage/README.md) |
| Server | [Transports, secure embedding, admission, and AOT](../setu-server/README.md) |
| Client | [Synchronous and async Java APIs, errors, TLS, and consumer progress](../setu-client/README.md) |
| Security | [Certificate identities, exact ACLs, and quotas](../setu-security/README.md) |
| Integration tests | [Functional socket and mTLS coverage](../setu-integration-tests/README.md) |
| Benchmarks | [Profiles, qualification criteria, and JFR interpretation](../setu-benchmark/README.md) |

Mermaid diagrams are stored as text alongside the explanations and render directly on GitHub. Local benchmark output belongs under ignored `logs/`, rather than in documentation. Published measurements must identify their source revision and qualification status.

The source of truth for standalone properties is [SetuServerMain.java](../setu-server/src/main/java/io/setu/server/SetuServerMain.java); for wire field order it is [ProtocolCodec.java](../setu-core/src/main/java/io/setu/core/protocol/ProtocolCodec.java). If a guide differs from the implementation, include the affected revision when [reporting the discrepancy](../SUPPORT.md).
