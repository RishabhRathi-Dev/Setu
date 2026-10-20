# Documentation

Start with the [project README](../README.md) for a runnable quickstart and supported behavior. These guides describe the current implementation; the module READMEs provide API and subsystem detail.

| Guide | What it explains |
| --- | --- |
| [Architecture](architecture.md) | Module dependencies, request flow, persistence, consumers, and backpressure |
| [Configuration](configuration.md) | Every standalone property, defaults, validation, and runtime controls |
| [Operations](operations.md) | Packaging, startup, logs, shutdown, recovery, migration, and troubleshooting |
| [Contributing](../CONTRIBUTING.md) | Build requirements, verification commands, and repository conventions |
| [Tooling](../scripts/README.md) | Launchers, certificate generation, benchmarks, JFR, and badge publication |

## Module references

| Module | Reference |
| --- | --- |
| Core | [Broker semantics and protocol v1](../setu-core/README.md) |
| Storage | [Durability, ownership, recovery, and legacy migration](../setu-storage/README.md) |
| Server | [Transports, secure embedding, admission, and AOT](../setu-server/README.md) |
| Client | [Java API, errors, TLS, and consumer progress](../setu-client/README.md) |
| Security | [Certificate identities, exact ACLs, and quotas](../setu-security/README.md) |
| Integration tests | [Functional socket and mTLS coverage](../setu-integration-tests/README.md) |
| Benchmarks | [Profiles, qualification criteria, and JFR interpretation](../setu-benchmark/README.md) |

Mermaid diagrams are stored as text alongside the explanations and render directly on GitHub. Local benchmark output belongs under ignored `logs/`, rather than in documentation. Published measurements must identify their source revision and qualification status.
