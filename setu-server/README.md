# setu-server

`setu-server` owns TCP connections, framing, buffer admission, secure request dispatch and the standalone broker launcher. Broker operations and wire types live in [setu-core](../setu-core/README.md); [setu-storage](../setu-storage/README.md) owns persistence.

Opt-in [managed groups and transactions](../docs/transactions.md) require FILE storage. Secure idempotent/transactional requests require exact producer identity grants in addition to topic/group grants; duplicate retries are authorized before dispatch. Per-request `ACCEPTED` acknowledgement is weaker than the default force boundary and adds no standalone property.

## Standalone JAR

From the repository root with Java 26 on `JAVA_HOME` and `PATH`:

```powershell
.\mvnw.cmd -pl setu-server -am package
.\scripts\start-broker.ps1 --config config/server.properties
```

On Linux/macOS, use `bash ./mvnw` and `bash ./scripts/start-broker.sh`. Defaults are plaintext `127.0.0.1:9092`, Chronicle Queue under `data/`, and rolling operational logs under `logs/`. Ctrl+C closes connections and storage. The properties launcher does not configure TLS/mTLS; use the embedded factory below.

Launchers read [config/jvm.options](../config/jvm.options), including ZGC, string deduplication and Chronicle's module/native access. A direct launch from the repository root is:

```powershell
java '@config/jvm.options' -jar setu-server/target/setu-server-1.0.0-SNAPSHOT.jar --config config/server.properties
```

Distribute the JAR with its adjacent `lib/` runtime dependencies and `config/jvm.options`. Application paths are relative to the launch working directory. `--help` prints usage. [server.properties](../config/server.properties) contains all supported settings; unknown properties fail startup.

FILE admission is configurable with `file.write.queue.max.bytes` (default 32 MiB) and `file.write.queue.max.requests` (default 1,024). Each accepts zero to disable that limit independently, including on recovered partitions. See [configuration](../docs/configuration.md#storage-and-retention) for a larger-queue example and the embedding `FileWriteQueueOptions` API. Force-before-ack durability and draining accepted writes on close are preserved.

Optional AOT training:

```powershell
.\scripts\start-broker.ps1 --train-aot
```

Training uses real clients through plaintext and mTLS in both storage modes, including ACL/quota denial, missing-certificate rejection and memory backpressure. The expected missing-certificate test can log `certificate_required`. Each path verifies 16,385 records across four partitions. CI checks all paths, active runtime options and cached classes from all five production modules, with JVM diagnostics separate from application markers.

The generated `target/setu-server.aot` is used automatically in strict mode. Regenerate it after changing JDK, platform, application or dependency JARs; `--no-aot` bypasses it. Tests do not require a cache. On the tested JDK, Chronicle's module options disable AOT class linking; shared application classes still load from the cache. JIT compilation remains responsible for native application code.

## Transports and lifecycle

The [container guide](../docs/containers.md) covers the source-built JDK 26 image, persistent FILE broker service, health checks and a Compose override for the LGTM stack. The image runs Java as a non-root user and loads the same JVM options as direct launches.

| Transport | Behavior |
| --- | --- |
| `TcpServer` / `transport=blocking` | Platform acceptor, virtual thread per connection; supports plaintext and embedded TLS/mTLS |
| `SelectorTcpServer` / `transport=selector` | Non-blocking plaintext sockets, selector multiplexing and virtual-thread request dispatch; one active request per connection |

`start()` binds synchronously; binding to port zero chooses an available port reported by `localAddress()`. `close()` stops acceptance, closes tracked connections and shuts down workers. The embedding application owns the server and broker, closing the server first.

`TcpServerOptions` defaults to 10,000 connections, backlog 256, a 60-second read/idle timeout, 128 MiB request credit, 128 MiB response credit, a 10-second session-setup deadline, and 30-second frame-receipt and reply-write deadlines. Excess connections are closed. Blocking TLS applies the read timeout to handshake and reads; selector mode uses it as an idle timeout. Both transports measure frame receipt from the first header byte through the entire body; trickled bytes cannot renew that deadline. Setup includes TLS handshake and authentication. These absolute deadlines remain active when the idle timeout is disabled and end before broker dispatch/long polling. The existing three-, five- and six-argument options constructors use the default setup/frame deadlines; the eight-argument constructor configures them. TLS is not supported by the selector transport.

## Secure broker wiring example

Generate development stores with [generate-certs.ps1](../scripts/generate-certs.ps1) or [generate-certs.sh](../scripts/generate-certs.sh). Set `SETU_CERT_PASSWORD` before generating the stores and running this example. The generated client has DNS SAN `setu-client`.

```java
import io.setu.core.broker.DefaultBroker;
import io.setu.server.SecureServerFactory;
import io.setu.security.authentication.MtlsAuthenticator;
import io.setu.security.authorization.InMemoryAuthorizer;
import io.setu.security.authorization.Permission;
import io.setu.security.certificates.CertificateValidator;
import io.setu.security.quota.QuotaManager;
import io.setu.security.quota.QuotaPolicy;
import io.setu.security.quota.TokenBucketRateLimiter;
import io.setu.security.tls.*;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

String password = System.getenv("SETU_CERT_PASSWORD");
var tls = new TlsConfiguration(true, "TLS",
        "certs/generated/broker.p12", password,
        "certs/generated/broker-trust.p12", password,
        null, true, List.of("TLSv1.3"), List.of());
var contexts = new TlsContextFactory(new SetuKeyManagerProvider(), new SetuTrustManagerProvider());
var acl = new InMemoryAuthorizer();
acl.grant("setu-client", "broker", Permission.VIEW_METADATA);
acl.grant("setu-client", "topic/orders", Permission.TOPIC_CREATE);
acl.grant("setu-client", "topic/orders", Permission.PRODUCE);
acl.grant("setu-client", "topic/orders", Permission.CONSUME);
var limiter = new TokenBucketRateLimiter();
limiter.registerPolicy("setu-client", new QuotaPolicy(1_000, 2_000, 10_000_000, 20_000_000));

try (var broker = new DefaultBroker(Path.of("data"), 1024 * 1024);
     var server = SecureServerFactory.createBroker(new InetSocketAddress("127.0.0.1", 9093),
             16 * 1024 * 1024, tls, contexts, new MtlsAuthenticator(new CertificateValidator()),
             acl, new QuotaManager(limiter), broker)) {
    server.start();
    new CountDownLatch(1).await(); // Replace with the application's shutdown signal.
}
```

Use the shared JVM options for the embedding application. Provision keys/passwords outside source control; the generated stores are development fixtures. [setu-security](../setu-security/README.md) documents certificate and policy contracts; [setu-client](../setu-client/README.md#tls-and-consumer-progress) documents client configuration.

## Request authorization contract

The secure path completes TLS and authenticates the client before protocol processing. `ProtocolRequestAuthorizationMetadataResolver` returns `RequestAuthorizationMetadata`, with additional grants represented by `AuthorizationRequirement`. Valid requests require these exact resources and permissions:

| Command | Resource | Permission |
| --- | --- | --- |
| `CREATE_TOPIC` | `topic/<topic>` | `TOPIC_CREATE` |
| `PRODUCE`, `PRODUCE_BATCH` | `topic/<topic>` | `PRODUCE` |
| `FETCH` | `topic/<topic>`; additionally `consumer-group/<group>` when a member is supplied | `CONSUME`; additionally `CONSUMER_GROUP_WRITE` for member-aware fetches |
| `JOIN_GROUP` | `consumer-group/<group>` | `CONSUMER_GROUP_CREATE` |
| `COMMIT_OFFSET`, `LEAVE_GROUP`, `HEARTBEAT` | `consumer-group/<group>` | `CONSUMER_GROUP_WRITE` |
| `METADATA` | `broker` | `VIEW_METADATA` |

After authorization, quotas charge one message per protocol request and the request-body byte count. A batch counts as one request. Denied operations do not reach the broker. Grants and quota registrations remain process-local. FILE consumer metadata recovers from the broker journal; IN_MEMORY metadata is volatile. Managed, identified and transactional command grants are specified in the [transaction security contract](../docs/transactions.md#security-and-verification).

Both transports preserve immutable frame ownership through built-in dispatch. Embedded plaintext pipelines should pass `BrokerRequestHandler` directly or use `RequestProcessor.plaintext(handler)`. Functional byte-array handlers remain supported through defensive compatibility copies. Shared framing and protocol details are in [protocol](../setu-core/README.md#protocol).

## Admission and failure behavior

Request credit is reserved before allocating a frame body and includes decoded payload/item allowances. Response credit is reserved before fetch materialization and encoding, and held through transmission. A quota failure returns `QUOTA_EXCEEDED` when a trustworthy request ID and error-response credit exist; otherwise the connection closes. These budgets account for specific allocations, not the entire JVM heap. Custom request processors must reserve response allocations through `ResponseBuffers.reserve(bytes)`.

| Setting | Behavior |
| --- | --- |
| `requests.max.bytes`, `responses.max.bytes` | Shared per-server buffer budgets; 128 MiB each by default |
| `write.timeout.millis` | Total reply-write deadline; 30 seconds by default; disconnects slow readers |
| `socket.timeout.millis` | Read/idle timeout; 60 seconds by default; zero disables only this timeout |
| `handshake.timeout.millis`, `frame.timeout.millis` | Positive absolute setup / frame-receipt deadlines; 10 / 30 seconds by default |
| `consumers.max`, `consumer.partitions.max` | Active member / summed partition membership limits; 10,000 / 100,000 by default |
| `memory.log.max.bytes` | Shared retained-memory log budget; 256 MiB default; zero disables it |
| `memory.retention.millis` | Optional lossy memory expiry; zero preserves acknowledgement-based reclamation |
| `file.retention.millis` | Optional completed-file expiry, measured from daily cycle end; zero disables it; newest file always remains |
| `ack.timeout.millis`, `consumer.timeout.millis` | Consumer acknowledgement and inactivity deadlines |

Fetch waits are capped at 30 seconds; client read timeouts must exceed the requested wait. Expired members must rejoin. Memory or file expiry can produce `OFFSET_OUT_OF_RANGE` with a resume offset. File cleanup runs at startup and every minute, independently of acknowledgements. [Storage](../setu-storage/README.md) documents group-commit limits, durability, recovery and legacy migration.

Malformed frame lengths, partial frames and TLS handshake failures close the connection. Protocol/application errors use structured responses when possible; unexpected I/O/runtime failures become `INTERNAL_ERROR`. Ordinary produce replies retain uncertain outcomes. FILE identified writes and transactions support retries with the same producer epoch, sequence and contents; see the transaction guide.

## Logs and tests

`TransportLifecycleTests` repeatedly disconnects incomplete frames, fails dispatch after reserving response credit, and shuts down active dispatch on both transports, checking zero remaining credits/connections. `CommandAuthorizationTests` covers every command through byte-array and owned-frame APIs, denies each missing exact grant and wrong principal, and verifies denied requests do not consume quota or reach dispatch. Real mTLS coverage remains in the integration reactor.

[logback.xml](../config/logback.xml) defaults to INFO (enable DEBUG explicitly for per-request diagnostics), persists `logs/setu.log`, rotates daily or at 100 MB, retains 14 days and caps archives at 2 GB. Use `SETU_LOG_DIR`, `SETU_LOG_LEVEL`, or `-Dsetu.log.level` to configure logging; the level property takes precedence. Record payloads, passwords and private keys are not logged. Untrusted diagnostic fields escape line separators and backslashes so request identities and error text cannot create forged log lines; protocol values remain unchanged. Connection/session and request diagnostics include correlation IDs and error details.

From the repository root with Java 26:

```powershell
.\mvnw.cmd -pl setu-server -am test
.\mvnw.cmd -pl setu-integration-tests -am test
```

Tests cover framing, dispatch, selector multiplexing, TLS/mTLS, authorization, quotas and admission. There is no selector TLS, persistent policy administration or multi-broker coordination.

## Operations and distributions

`operations.port=-1` disables the optional HTTP endpoint; `0..65535` binds `/metrics` and `/ready` on 127.0.0.1. See [metric definitions and embedding](../docs/observability.md). `journal.compact.bytes=67108864` triggers FILE metadata checkpointing; zero disables the automatic trigger. See [checkpoint publication and compatibility](../docs/journal-checkpoints.md).

The [LGTM setup](../docs/observability.md#local-lgtm-stack) collects broker, transport and JVM metrics into Mimir and optional OTLP logs/traces into Loki/Tempo, with a provisioned Grafana dashboard. `BrokerRequestHandler` creates dispatch spans through the OpenTelemetry API; export requires an external Java agent or an embedding application's SDK. A tracer can be injected through its two-argument constructor. The protocol requires [trace-context fields](../setu-core/README.md#request-trace-context) on every request, propagating W3C parents and vendor state from clients; empty or invalid parents start fresh roots. Propagation cannot be disabled. Trace context grants no permissions and does not bypass authentication, ACLs or quotas. In embedding applications, pass the listener's `operationalGauges` supplier to the four-argument `OperationsServer` constructor to include connection/buffer metrics.

After packaging, `python scripts/package-distribution.py` creates a versioned ZIP with adjacent runtime libraries, launchers, configuration and SHA-256 manifests. `python scripts/verify-distribution.py` verifies hashes and runs the shipped launcher from an extracted temporary directory. [Distribution CI](../.github/workflows/distribution.yml) exercises the recovery demo and distribution on Windows and Linux.
