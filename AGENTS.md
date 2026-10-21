# Repository knowledge base

## Project and scope

Setu is a single-node Java message broker with partitioned logs and a binary TCP protocol. It supports durable Chronicle Queue storage (`FILE`) and bounded volatile storage (`IN_MEMORY`), blocking and selector transports, a synchronous Java client, and embedded mTLS/ACL/quota enforcement.

Read [README.md](README.md) for setup and supported behavior, [CONTRIBUTING.md](CONTRIBUTING.md) for the contribution workflow, and the owning module's README before changing a subsystem. The implementation and build files are authoritative; update documentation when their contracts change.

## Toolchain and local workflow

- Use **JDK 26**, with `JAVA_HOME` and `PATH` pointing to that JDK. The compiler release is declared in [pom.xml](pom.xml).
- Use the Maven Wrapper: `.\mvnw.cmd` on Windows; `bash ./mvnw` on Linux/macOS. The distribution is pinned in [.mvn/wrapper/maven-wrapper.properties](.mvn/wrapper/maven-wrapper.properties).
- Python tools require **Python 3.10+** and use the standard library.
- Run commands from the repository root. Use `-am` to include a module's reactor dependencies.
- Resource-enforced benchmark profiles require Linux/WSL, systemd, and cgroup v2. Portable functional tests and JFR smoke captures do not require that benchmark environment.
- Inspect `git status --short` before editing and preserve existing user changes. Do not clean runtime directories as part of a source or documentation change.

## Module map

| Path | Owns | Start here |
| --- | --- | --- |
| `setu-storage/` | Log API, payload ownership, memory budget, Chronicle persistence, group commit, retention, migration | [Storage guide](setu-storage/README.md), `MessageLog`, `MessageLogs`, `MessageValue` |
| `setu-core/` | Broker semantics, topics, partitions, membership, offsets, long polling, wire codec | [Core guide](setu-core/README.md), `DefaultBroker`, `ProtocolCodec` |
| `setu-security/` | TLS material, DNS SAN identities, exact ACL grants, principal quotas | [Security guide](setu-security/README.md), `TlsContextFactory`, `InMemoryAuthorizer`, `QuotaManager` |
| `setu-server/` | TCP transports, framing/admission, dispatch, secure composition, standalone main, AOT training | [Server guide](setu-server/README.md), `SetuServerMain`, `SecureServerFactory` |
| `setu-client/` | Serialized synchronous requests, correlation checking, TLS hostname verification | [Client guide](setu-client/README.md), `SetuClient` |
| `setu-integration-tests/` | Public API checks through real TCP and mTLS sockets | [Integration guide](setu-integration-tests/README.md) |
| `setu-benchmark/` | Throughput, latency, resource/JFR evidence, crash/chaos workloads | [Benchmark guide](setu-benchmark/README.md) |
| `config/` | Standalone defaults, shared JVM options, packaged Logback configuration | [Configuration reference](docs/configuration.md) |
| `examples/` | Source-launchable Java programs for clients, embedding, recovery, retention | [Examples guide](examples/README.md) |
| `scripts/` | Launchers, certificate generation, workload orchestration, reporting, badge publication | [Tooling guide](scripts/README.md) |

Production dependencies: `core → storage`; `client → core + security`; `server → core + client + security`. The server's client dependency supports AOT training. Keep security independent of broker protocol and persistence types. Keep sockets and policy enforcement out of core and storage.

## Source entry points

- [DefaultBroker.java](setu-core/src/main/java/io/setu/core/broker/DefaultBroker.java): topic validation/discovery, membership, progress, maintenance, and request execution.
- [ProtocolCodec.java](setu-core/src/main/java/io/setu/core/protocol/ProtocolCodec.java), [FrameCodec.java](setu-core/src/main/java/io/setu/core/protocol/FrameCodec.java), and [ErrorCode.java](setu-core/src/main/java/io/setu/core/protocol/ErrorCode.java): field order, framing, protocol limits, and error IDs.
- [MessageLogs.java](setu-storage/src/main/java/io/setu/storage/log/MessageLogs.java): storage-engine construction and defaults.
- [ChronicleMessageLog.java](setu-storage/src/main/java/io/setu/storage/log/ChronicleMessageLog.java), [ChronicleFileSync.java](setu-storage/src/main/java/io/setu/storage/log/ChronicleFileSync.java), and [GroupCommitMessageLog.java](setu-storage/src/main/java/io/setu/storage/log/GroupCommitMessageLog.java): durable append, forcing, admission, recovery, and retention.
- [InMemoryMessageLog.java](setu-storage/src/main/java/io/setu/storage/log/InMemoryMessageLog.java) and [MemoryLogBudget.java](setu-storage/src/main/java/io/setu/storage/log/MemoryLogBudget.java): immutable publication, reclamation, and whole-batch memory admission.
- [SetuServerMain.java](setu-server/src/main/java/io/setu/server/SetuServerMain.java): accepted standalone property names, defaults, validation, and lifecycle.
- [TcpServerOptions.java](setu-server/src/main/java/io/setu/server/tcp/TcpServerOptions.java), [FramedConnectionHandler.java](setu-server/src/main/java/io/setu/server/connection/FramedConnectionHandler.java), and [SelectorTcpServer.java](setu-server/src/main/java/io/setu/server/tcp/SelectorTcpServer.java): transport budgets, framing, and write deadlines.
- [SecureServerFactory.java](setu-server/src/main/java/io/setu/server/SecureServerFactory.java) and [ProtocolRequestAuthorizationMetadataResolver.java](setu-server/src/main/java/io/setu/server/security/ProtocolRequestAuthorizationMetadataResolver.java): secure wiring and command-to-permission/resource mapping.
- [SetuClient.java](setu-client/src/main/java/io/setu/client/SetuClient.java): client factories, exchanges, timeout parameters, and response ownership.

## Contracts to preserve

### Records, offsets, and consumers

- Ordering and dense logical offsets apply within a partition. Applications select partitions explicitly; there is no automatic assignment or rebalance.
- Batch produce returns the first assigned offset. Whole-batch admission rejection must leave offsets and records unchanged.
- Fetch returns whole records within its payload-byte budget. An oversized first record yields an empty page.
- Acknowledgement uses the **next** offset after processing. Heartbeats maintain liveness but do not acknowledge records.
- Memory members join at the current partition end. Join before producing records they need; no members allows immediate reclamation. File members start at zero, subject to retained history.
- FILE memberships and member acknowledgements recover from `@consumer-state/`; IN_MEMORY metadata is volatile. Leave and expiry remove progress. Fetched positions do not recover.
- Long polling is capped at 30 seconds. Client read timeouts must exceed the requested wait.

### Storage and ownership

- Durable success follows forcing every touched queue/index/header and roll-metadata file. Preserve the committed boundary and failure semantics when changing the write path.
- Keep one owner per durable partition, enforced by the exclusive lock. Accepted group-commit work drains on close.
- Setu offsets are independent of Chronicle indexes. Recovery validates committed documents and dense offsets; malformed committed data fails startup.
- File retention expires whole completed UTC daily cycles, ignores consumer progress, and always preserves the newest populated cycle. Preserve the retention checkpoint with the queue files. Memory timed retention is separately configured and lossy.
- Public byte-array APIs copy defensively. Internal immutable frames/slices may share storage; retaining a slice can keep the backing frame alive. Preserve ownership and memory accounting through admission, rejection, reclamation, and shutdown.
- A failed or lost produce reply can have an uncertain outcome. Do not introduce claims of idempotent retries or exactly-once delivery.

### Transport and security

- Reserve request credit before body allocation and response credit before materialization/encoding; hold response credit through transmission. Budgets are not whole-process memory limits.
- Check `BrokerResponse.errorCode()` explicitly in clients and examples; broker denials do not automatically throw.
- The properties launcher serves plaintext. TLS/mTLS is configured through embedding with `SecureServerFactory`; selector transport is plaintext only.
- Authenticate before dispatch; enforce default-deny exact ACLs and atomic request/byte quotas. A valid certificate alone grants no permission.
- Preserve client hostname verification. Development credentials are generated locally and are not source fixtures.

## Verification commands

Choose checks based on the affected contract. These are PowerShell commands; substitute `bash ./mvnw` for `.\mvnw.cmd` on Linux/macOS.

| Change | Check |
| --- | --- |
| Production behavior/API, including real TCP/mTLS | `.\mvnw.cmd -pl setu-integration-tests -am test` |
| Storage | `.\mvnw.cmd -pl setu-storage test` |
| Core or protocol | `.\mvnw.cmd -pl setu-core -am test` |
| Security | `.\mvnw.cmd -pl setu-security test` |
| Server packaging or runnable examples | `.\mvnw.cmd -pl setu-server -am package`, then the relevant [example](examples/README.md) |
| Benchmark helpers without chaos/load entry points | `.\mvnw.cmd -pl setu-benchmark -am '-Dtest=*Tests,!*ChaosTests,!*BenchmarkTests' '-Dsurefire.failIfNoSpecifiedTests=false' test` |
| Python tooling, reports, badge publication | `python -m unittest discover -s scripts -p 'test_*.py'` |
| Any change | `git diff --check` and review the resulting diff |

The full root Maven reactor includes stress and benchmark workloads. Do not use an unfiltered root `test` as a routine smoke check. Surefire reports live in each module's `target/surefire-reports/`. Add behavioral coverage for changed contracts, especially recovery, admission failures, and resource release. For documentation-only changes, verify local links, commands against their source, and generated-marker integrity; Java suites are unnecessary unless behavior or examples change.

Maven's own JVM flags are in [.mvn/jvm.config](.mvn/jvm.config). Surefire and the launchers apply [config/jvm.options](config/jvm.options), including required Chronicle module/native access. Preserve these flags for file-backed examples and direct launches. Keep the packaged server JAR beside `setu-server/target/lib/`.

## Documentation and benchmark publication

- Cross-cutting guides live under [docs/](docs/README.md); subsystem contracts live beside their module. Update both when a change crosses boundaries.
- Configuration changes must update `SetuServerMain`, `config/server.properties`, [docs/configuration.md](docs/configuration.md), and affected examples/guides together.
- Protocol changes must update codec validation, public client/server behavior, tests, and the [protocol reference](setu-core/README.md#protocol-v1).
- Keep Windows and Bash entry points aligned. Preserve LF in shell scripts and CRLF in `.cmd` files as defined in [.gitattributes](.gitattributes).
- `benchmark-badges/` contains tracked CI evidence, not disposable generated builds. Do not replace it with invented or stale performance claims.
- Preserve exactly one start/end pair for each root README marker: `benchmark-metrics-badges`, `benchmark-target-diagnostics`, and `benchmark-capacity-details`. Publication scripts own their contents; put manual prose outside them.
- Performance evidence must identify source revision, JDK, host/resource budget, filesystem/media, record/batch size, warmup, duration, repetitions, and qualification failures. A smoke run, an unqualified run, or an old backend's result does not establish current sustained capacity.
- Preserve benchmark qualification criteria. Report-only expectation misses remain unqualified; execution failures remain errors.

## Repository hygiene and boundaries

Follow the existing Java/package style and [.editorconfig](.editorconfig). Keep changes focused and avoid unrelated reformatting or new dependencies without a concrete need.

Do not commit runtime data, generated keys/stores, passwords, logs, JFR recordings, heap dumps, AOT caches, build outputs, or IDE state. Respect [.gitignore](.gitignore). Leave local data and credentials intact. Follow [SECURITY.md](SECURITY.md) for vulnerability reports.

Replication, failover, producer deduplication, exactly-once transactions, automatic group coordination, configurable acknowledgement modes, and distributed security administration are not implemented. Async clients may retry reads but never uncertain writes. New file documents carry CRC32C; legacy documents remain unchecked. Do not document additional capabilities without implementing and verifying them.
