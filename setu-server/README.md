# Setu Server

`setu-server` implements Setu's first broker network path. It accepts TCP connections, applies framing and protocol validation, optionally establishes TLS/mTLS, enforces authorization and quotas, and dispatches operations to `setu-core`. `setu-core` can use either in-memory or file-backed append-only logs from `setu-storage`; `setu-client` shares the protocol codec.

## Current implementation status

### Implemented

- A version 1 binary protocol with command IDs, correlation IDs, strict payload validation, and structured response/error codes.
- A shared frame codec and protocol codec used by server and client.
- Broker commands for topic creation, metadata, produce, fetch, consumer-group membership/heartbeat, and offset commits.
- A segmented file-backed partition log with startup recovery and a broker facade in `setu-core`.
- A default-deny, exact-match in-memory ACL authorizer in `setu-security`.
- A built-in command-to-resource/permission resolver.
- TLS client and server setup with mTLS, DNS SAN identity authentication, and hostname verification on clients.
- Structured authorization, quota, broker, and protocol error responses.
- Configurable connection count, accept backlog, and socket/idle timeout limits.
- A selector-based plaintext transport and a TLS transport using a virtual thread per connection.
- Socket-level TLS broker integration tests and selector multiplexing tests.

### Remaining limitations

- Selector-based TLS transport. The selector implementation handles plaintext channels; TLS currently uses blocking socket I/O on virtual threads.
- Durable control-plane metadata: ACL grants, committed offsets, and group membership are process-local. PostgreSQL is not wired.
- Full consumer-group coordination and partition assignment/rebalancing.
- File retention, checksums, and a persisted offset index. Partitions rotate across segment files and rebuild their offset indexes at startup.
- A protocol-level response if the TLS handshake fails before a protocol connection exists. Malformed frame lengths also close the connection because no trustworthy request ID is available yet.
- Production policy administration, ACL persistence/reload, and distributed quotas.

`InMemoryAuthorizer` denies by default until an exact principal/resource/permission grant is registered. Its grants are lost on restart. `SecureServerFactory.createBroker` wires the built-in protocol resolver and broker dispatcher; callers still provide TLS files, policy grants, and quota policies.

## Package layout

```text
io.setu.server
├── SecureServerFactory.java
├── codec
│   └── FrameCodec.java
├── connection
│   ├── ConnectionHandler.java
│   ├── ConnectionSession.java
│   ├── ConnectionSessionFactory.java
│   ├── FramedConnectionHandler.java
│   └── PlaintextConnectionSessionFactory.java
├── dispatcher
│   ├── BrokerRequestHandler.java
│   ├── RequestHandler.java
│   └── RequestProcessor.java
├── security
│   ├── ProtocolRequestAuthorizationMetadataResolver.java
│   ├── RequestAuthorizationMetadata.java
│   ├── RequestAuthorizationMetadataResolver.java
│   ├── SecuredRequestProcessor.java
│   └── TlsConnectionSessionFactory.java
└── tcp
    ├── SelectorTcpServer.java
    ├── TcpServer.java
    └── TcpServerOptions.java
```

Shared wire types and the protocol codec live under `io.setu.core.protocol`. Broker operations are under `io.setu.core.broker`; file-log APIs are under `io.setu.storage.log`.

The main runtime flow is:

```text
TCP acceptor
   │ SocketChannel
   ▼
ConnectionHandler
   │
   ▼
FramedConnectionHandler
   ├── ConnectionSessionFactory.open(channel)
   │      ├── plaintext session, or
   │      └── TLS handshake → client certificate → AuthenticationProvider
   │
   ├── FrameCodec.readFrame(...)
   ├── RequestProcessor.process(identity, request)
   │      ├── resolve request → resource + permission
   │      ├── Authorizer.authorize(...)
   │      ├── QuotaManager.check(...)
   │      └── RequestHandler.handle(request)
   └── FrameCodec.writeFrame(...)
```

This division keeps socket acceptance, connection setup, framing, request policy, and broker operation behind separate components. `SecureServerFactory` is the composition root for a ready-to-run secure server.

## Standalone JAR

Build and launch the local-development broker from the repository root with Java 25:

```powershell
.\mvnw.cmd -pl setu-server -am package
java -jar setu-server/target/setu-server-1.0.0-SNAPSHOT.jar --config config/server.properties
```

The runnable JAR requires its adjacent `target/lib/` directory, which Maven fills with runtime dependencies. Distribute the JAR and `lib/` together. `--help` prints usage; running without arguments selects plaintext `127.0.0.1:9092`, file storage under `./data`, and rolling operational logs under `./logs`. Paths are relative to the launch working directory. Ctrl+C closes connections and the broker through a shutdown hook.

`config/server.properties` exposes the bind address, storage mode (`FILE` or `IN_MEMORY`), data directory, segment and record size, connection cap, backlog, timeouts, and transport (`blocking` or `selector`). This launcher serves plaintext; the existing secure embedding API below provides TLS/mTLS, authentication, authorization, and quotas. The capacity workload also uses the plaintext path and bypasses quotas, so raising quota rates cannot change its throughput.

For `IN_MEMORY`, `memory.log.max.bytes` bounds retained log payload/chunk accounting across all partitions (256 MiB by default; zero disables it). If a single record or complete batch would exceed the remaining budget, produce returns `QUOTA_EXCEEDED` before appending it. The connection remains usable, and fetch/acknowledgement requests can free capacity so producers can retry with backoff. This budget covers retained message logs; concurrent protocol buffers and file indexes need separate admission limits.

The packaged Logback configuration persists operational logs as `logs/setu.log`, rotates at 100 MB or daily, retains up to 14 days, and caps archives at 2 GB. Set `SETU_LOG_DIR` to change the log directory, or use `-Dlogback.configurationFile=<path>` before `-jar` to provide a different logging configuration. Operational logs and durable message segment files are separate artifacts. See [server.properties](../config/server.properties) and [logback.xml](../config/logback.xml).

Operational logging defaults to `DEBUG`. Use `SETU_LOG_LEVEL=INFO` or `-Dsetu.log.level=INFO` before `-jar` to reduce request logging; the JVM property takes precedence. DEBUG records command, correlation ID, topic/partition, offsets, frame sizes, result counts, and elapsed request/append times. Connection events identify sessions and peers; security decisions identify principals and resources. Recovery reports segment counts and offsets, and warns when an invalid tail is truncated. Unexpected failures retain their complete exception chain and stack trace in the logs while clients receive the existing structured errors. Record payloads, passwords, and private keys are not logged.

The synchronous client and blocking server buffer socket reads/writes to amortize frame-header I/O while retaining TCP_NODELAY and a flush after every complete response/request. Protocol validation inspects payload lengths without cloning, and the encoder reads owned payloads internally and sizes batch/fetch buffers before writing. Public payload accessors continue to return defensive copies. These changes retain the wire format, record ordering, memory admission, and forced file durability.

Packaging keeps Java bytecode on the JVM. It provides a launchable distribution; performance claims come from benchmarks.

## Embedded TCP server lifecycle

`TcpServer` takes a bind address, a `ConnectionHandler`, and optionally `TcpServerOptions`:

```java
TcpServer server = new TcpServer(
        new InetSocketAddress("127.0.0.1", 9092),
        connectionHandler
);

server.start();
try {
    System.out.println("Listening on " + server.localAddress());
    // Keep the owning application alive while the broker is serving.
} finally {
    server.close();
}
```

`start()` binds synchronously and starts the accept loop on a named platform thread. It throws `IOException` if the bind fails and `IllegalStateException` if called while already running. Binding to port `0` asks the operating system for an available port; `localAddress()` returns the actual bound address.

Each accepted `SocketChannel` is submitted to a virtual-thread-per-task executor. The accept loop continues to accept other connections while a connection handler blocks on network input. `close()` stops acceptance, closes the listening channel and tracked client channels, shuts down the connection executor, and waits up to five seconds for the accept thread. The server may be started again after it has closed.

`TcpServerOptions` defaults to a maximum of 10,000 active connections, an accept backlog of 256, and no socket timeout. Set `socketTimeoutMillis` to bound blocking TLS handshake and socket-read duration. Connections over the configured limit are closed as soon as they are accepted. The TLS server path applies the same timeout to handshake and subsequent reads.

For plaintext deployments, `SelectorTcpServer` multiplexes non-blocking channels on a selector and dispatches complete requests on virtual threads. It accepts one request at a time per connection, stops reading while the response is being produced/sent, and applies `socketTimeoutMillis` as an idle timeout. The selector server does not provide TLS or peer authentication. Use `TcpServer` with the secure factory for TLS/mTLS.

## Frame format

TCP is a byte stream, so a read is not guaranteed to return one complete request. `FrameCodec` defines the current framing rule:

```text
┌────────────────────────────┬───────────────────────────┐
│ 4-byte signed big-endian N │ N body bytes              │
└────────────────────────────┴───────────────────────────┘
```

The length counts only the frame body; it does not include the four-byte header. The current default maximum body size is `16 * 1024 * 1024` bytes (16 MiB). `FramedConnectionHandler` accepts a different non-negative limit. A zero-byte body is permitted by the frame codec but rejected by the protocol decoder as an incomplete request.

`readFrame` returns `null` only if the peer closes cleanly before the first byte of a new header. A partial header or partial body raises `IOException`. Negative lengths and lengths above the configured maximum also raise `IOException`. `writeFrame` validates the response length, writes the four-byte header and body, then flushes the output stream.

The frame body is a versioned binary protocol message, encoded by `ProtocolCodec` in `setu-core`. See [Protocol v1](#protocol-v1) for the header and command payloads.

## Protocol v1

All integers are big-endian. Each framed request body begins with this 12-byte header:

| Field | Width | Meaning |
| --- | ---: | --- |
| Magic | 4 bytes | `0x53455455` (`SETU`) |
| Version | 1 byte | `1` |
| Message kind | 1 byte | `0` request, `1` response |
| Command ID | 2 bytes | Command number from the table below |
| Correlation ID | 4 bytes | Non-negative client ID echoed in the response |

Strings use a signed 32-bit byte length followed by UTF-8 bytes, limited to 4,096 bytes. Byte arrays use a signed 32-bit length and are bounded by the frame limit. Topic names currently accept ASCII letters, digits, `.`, `_`, and `-`, with a maximum length of 249 characters; `.` and `..` are rejected. Create-topic requests are limited to 1,024 partitions. Fetch requests may ask for up to 15 MiB of payload bytes.

| Command | ID | Request body fields |
| --- | ---: | --- |
| `PRODUCE` | 1 | topic, partition `int32`, value bytes |
| `FETCH` | 2 | topic, partition `int32`, offset `int64`, max bytes `int32`, optional group ID + member ID |
| `COMMIT_OFFSET` | 3 | group ID, topic, partition `int32`, next offset `int64`, optional member ID (acknowledgement) |
| `JOIN_GROUP` | 4 | group ID, topic, member ID |
| `LEAVE_GROUP` | 5 | group ID, member ID |
| `HEARTBEAT` | 6 | group ID, member ID |
| `METADATA` | 7 | empty |
| `CREATE_TOPIC` | 8 | topic, partition count `int32` |

Responses repeat the command and correlation ID, then include a 16-bit error code and a length-prefixed UTF-8 message. Successful `PRODUCE` responses include the assigned `int64` offset. Successful `FETCH` responses include a count followed by `(offset int64, value bytes)` records. Successful `METADATA` responses include a count followed by `(topic, partition count int32)` entries. Other successful commands have an empty response body. Error responses omit the successful command body.

Defined error IDs are `NONE=0`, `BAD_REQUEST=1`, `UNSUPPORTED_VERSION=2`, `UNKNOWN_COMMAND=3`, `UNKNOWN_TOPIC=4`, `UNKNOWN_PARTITION=5`, `AUTHENTICATION_FAILED=6`, `AUTHORIZATION_DENIED=7`, `QUOTA_EXCEEDED=8`, `INTERNAL_ERROR=9`, `OFFSET_OUT_OF_RANGE=10`, `UNSUPPORTED_OPERATION=11`, and `TOPIC_ALREADY_EXISTS=12`.

`ProtocolCodec` validates message kind, magic, version, command, lengths, UTF-8, command-specific required fields, and trailing bytes. Group-aware fetch and member acknowledgements append optional fields to their command bodies; legacy v1 bodies without those fields remain valid. `SetuClient` verifies that the response command and correlation ID match the request. Protocol v1 is the initial wire contract; version negotiation beyond rejecting unsupported versions is not implemented.

## Plaintext connection path

For tests or local development, use `FramedConnectionHandler` with a `RequestHandler` and `TcpServer`, or use the selector transport:

```java
RequestHandler handler = request -> processRequest(request);
ConnectionHandler connections = new FramedConnectionHandler(handler);

TcpServer server = new TcpServer(bindAddress, connections);
```

This constructor uses `PlaintextConnectionSessionFactory` and passes `null` as the authenticated identity. It does not provide confidentiality, peer authentication, authorization, or quotas by itself. Do not expose it on an untrusted network. For an explicitly local plaintext broker, wrap a `BrokerRequestHandler` around `DefaultBroker`. For secure operation, use `SecureServerFactory.createBroker`.

The lower-level `FramedConnectionHandler` constructor accepts a `ConnectionSessionFactory` and a `RequestProcessor`, which allows alternative connection establishment or request pipelines to be supplied without changing the TCP acceptor.

## TLS and mTLS request flow

`SecureServerFactory` assembles the supported secure path:

1. `TlsConnectionSessionFactory` wraps an accepted socket in a server-mode `SSLSocket`.
2. It applies configured enabled protocol and cipher-suite lists when those lists are non-empty.
3. It requires client authentication and performs the TLS handshake before reading any Setu frame.
4. It passes the established `SSLSession` to the configured `AuthenticationProvider`.
5. With the supplied `MtlsAuthenticator`, the peer certificate is validated and converted into an `AuthenticationResult` principal.
6. `FramedConnectionHandler` reads one frame at a time and passes the authenticated identity and body to the request processor.
7. `SecuredRequestProcessor` resolves the body to a resource and permission, authorizes the identity, checks the principal's quota, and only then calls the broker `RequestHandler`.

The secure connection factory rejects configurations where TLS is disabled or `clientAuthenticationRequired` is false. It always requests a client certificate at the socket layer. The `TlsContextFactory` loads server keys and trusted client issuers using the configured keystore and truststore providers. Trust-chain checks occur in the JSSE handshake; `MtlsAuthenticator` then applies Setu's certificate validity/EKU and identity checks.

### Secure broker wiring example

This example wires the built-in decoder, command permission resolver, broker dispatcher, and exact-match ACL. The ACL and quota registrations below are in memory and must be provisioned by the application at startup.

```java
import io.setu.security.authentication.MtlsAuthenticator;
import io.setu.security.authorization.InMemoryAuthorizer;
import io.setu.security.authorization.Permission;
import io.setu.security.certificates.CertificateValidator;
import io.setu.security.quota.QuotaPolicy;
import io.setu.security.quota.QuotaManager;
import io.setu.security.quota.TokenBucketRateLimiter;
import io.setu.security.tls.SetuKeyManagerProvider;
import io.setu.security.tls.SetuTrustManagerProvider;
import io.setu.security.tls.TlsConfiguration;
import io.setu.security.tls.TlsContextFactory;
import io.setu.server.SecureServerFactory;
import io.setu.core.broker.DefaultBroker;
import io.setu.server.tcp.TcpServer;
import io.setu.server.tcp.TcpServerOptions;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.List;

TlsConfiguration tls = new TlsConfiguration(
        true,
        "TLS",
        "config/broker-keystore.p12",
        System.getenv("SETU_KEYSTORE_PASSWORD"),
        "config/client-truststore.p12",
        System.getenv("SETU_TRUSTSTORE_PASSWORD"),
        null,
        true,
        List.of("TLSv1.3"),
        List.of()
);

TlsContextFactory tlsContexts = new TlsContextFactory(
        new SetuKeyManagerProvider(),
        new SetuTrustManagerProvider()
);
MtlsAuthenticator authenticator = new MtlsAuthenticator(new CertificateValidator());

InMemoryAuthorizer acl = new InMemoryAuthorizer();
acl.grant("orders-service", "broker", Permission.VIEW_METADATA);
acl.grant("orders-service", "topic/orders", Permission.TOPIC_CREATE);
acl.grant("orders-service", "topic/orders", Permission.PRODUCE);

TokenBucketRateLimiter limiter = new TokenBucketRateLimiter();
limiter.registerPolicy("orders-service", new QuotaPolicy(
        1_000, 2_000,       // messages per second, message burst capacity
        10_000_000, 20_000_000 // bytes per second, byte burst capacity
));
QuotaManager quotas = new QuotaManager(limiter);

try (DefaultBroker broker = new DefaultBroker(Path.of("data"), 15 * 1024 * 1024);
     TcpServer server = SecureServerFactory.createBroker(
        new InetSocketAddress("0.0.0.0", 9092),
        16 * 1024 * 1024,
        tls,
        tlsContexts,
        authenticator,
        acl,
        quotas,
        broker,
        new TcpServerOptions(1_000, 128, 30_000)
     )) {
    server.start();
    new CountDownLatch(1).await(); // Replace with your application's shutdown signal.
}
```

The example intentionally does not grant `CONSUME`, so a fetch request receives `AUTHORIZATION_DENIED`. Keep passwords out of source code and version control. Supply keystore/truststore files through protected deployment configuration. `DefaultBroker` owns its file logs; the application owns and closes the broker after closing the server.

## Request authorization contract

`ProtocolRequestAuthorizationMetadataResolver` decodes the complete v1 request and maps it to `RequestAuthorizationMetadata(resource, permission)` before dispatch. Current mappings are:

- `PRODUCE` → `topic/<name>` + `PRODUCE`.
- `FETCH` → `topic/<name>` + `CONSUME`.
- `CREATE_TOPIC` → `topic/<name>` + `TOPIC_CREATE`.
- `COMMIT_OFFSET` → `consumer-group/<group>` + `CONSUMER_GROUP_WRITE`.
- `JOIN_GROUP` → `consumer-group/<group>` + `CONSUMER_GROUP_CREATE`.
- `LEAVE_GROUP` and `HEARTBEAT` → `consumer-group/<group>` + `CONSUMER_GROUP_WRITE`.
- `METADATA` → `broker` + `VIEW_METADATA`.

The available permissions also include `TOPIC_DELETE`, `CONSUMER_GROUP_READ`, `ACL_READ`, and `ACL_WRITE`; those are not currently exercised by a v1 command.

`SecuredRequestProcessor` constructs `AuthorizationContext` using the authenticated principal, resolver-provided resource, and resolver-provided permission. It calls `Authorizer.authorize(identity, context)`. If the identity is absent, the resolver fails, or the authorizer denies or returns no decision, the broker handler is not called.

After authorization succeeds, it calls `QuotaManager.check(principal, 1, request.length)`. Each frame counts as one message, and only the frame body length counts toward bytes; the four-byte frame header and response bytes are not charged. A rejected quota also prevents broker dispatch. The current gate rejects immediately; it does not sleep or throttle the connection.

## Quota behavior

`QuotaManager` delegates to a `RateLimiter`. The supplied `TokenBucketRateLimiter` supports per-principal message and byte buckets. Register one `QuotaPolicy` per known principal:

```java
limiter.registerPolicy("orders-service", new QuotaPolicy(
        messagesPerSecond,
        messageBurstCapacity,
        bytesPerSecond,
        byteBurstCapacity
));
```

All values must be positive, and each burst capacity must be at least its refill rate. For each request, the limiter must have capacity in both buckets; it consumes neither bucket unless both can satisfy the request. An unknown principal has no registered policy and is denied by the token-bucket implementation. Quotas count one message and the request body bytes for every accepted request. The quota map is local to one broker process; distributed/cluster-wide quotas, per-resource quotas, connection quotas, and quota persistence are not implemented.

## Broker and storage behavior

`DefaultBroker` is the first single-process broker implementation. It creates topics with 1 to 1,024 partitions, appends records, fetches by offset, returns topic metadata, and tracks simple group membership and committed offsets.

Each topic partition stores one or more segments under:

```text
<data-directory>/<topic>/partition-<n>/<segment-base-offset>.log
```

The current log record is a four-byte signed length followed by the record bytes. Offsets are zero-based record positions. `SegmentedFileMessageLog` scans each segment at startup to rebuild its in-memory offset index, truncates an invalid or partial tail in the active segment to the last valid record, and calls `FileChannel.force(true)` before `PRODUCE` or `PRODUCE_BATCH` returns success. Single-record produce syncs once per record; a batch syncs once per touched segment. Segment rotation defaults to 1 GiB and can be configured through `DefaultBroker`. There is no record checksum, retention, or persisted offset index.

Topics and partition logs survive restart because they are represented on disk. Committed consumer offsets and group membership currently live only in memory and are lost when the broker restarts. PostgreSQL and the control-plane metadata model from the root README are not connected to `DefaultBroker` yet.

## Client

`setu-client` provides `SetuClient`, a synchronous protocol v1 client. It can open plaintext connections for local development or TLS connections with a client certificate. TLS connections apply the configured protocol/cipher lists and verify the broker hostname against the broker certificate. Requests on one client instance are serialized so response correlation remains unambiguous.

```java
import io.setu.client.SetuClient;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.ErrorCode;
import io.setu.security.tls.TlsConfiguration;
import io.setu.security.tls.TlsContextFactory;

try (SetuClient client = SetuClient.connectTls(
        "broker.example.internal", 9092, clientTlsConfiguration, tlsContextFactory,
        5_000, 30_000, 16 * 1024 * 1024)) {
    BrokerResponse created = client.createTopic("orders", 4);
    if (created.errorCode() != ErrorCode.NONE) {
        throw new IllegalStateException(created.errorMessage());
    }

    BrokerResponse produced = client.produce("orders", 0, "order-123".getBytes());
    BrokerResponse fetched = client.fetch("orders", 0, produced.offset(), 1024 * 1024);
}
```

The timeout arguments are connect timeout and socket read timeout in milliseconds. A zero read timeout means no client-side read timeout. The client currently exposes generic `BrokerRequest`/`BrokerResponse` exchange plus convenience methods for topic creation, single and batched produce, fetch, metadata, and consumer group management; it does not yet expose asynchronous producers/consumers or retries.

## Failure and connection behavior

Once framing has produced a body, protocol and application failures use the structured protocol response and the connection remains available for the next request. Transport and framing errors still end the connection:

| Condition | Current behavior |
| --- | --- |
| Bind fails | `start()` throws `IOException`. |
| TLS handshake, hostname validation, or client-certificate authentication fails | Session creation fails; the client channel is closed before request processing. TLS has no Setu protocol response because the protocol session was not established. |
| Clean peer close between frames | The framed loop exits and closes the session. |
| Partial frame header/body, negative frame length, or oversized frame | The frame codec cannot trust a complete request; the connection is closed. |
| Malformed protocol body or unsupported protocol version | An error response is sent. The connection remains open. |
| Unknown topic/partition, out-of-range offset, or duplicate topic | A broker error response is sent. |
| Authorization denial or security quota exceedance | `AUTHORIZATION_DENIED` or `QUOTA_EXCEEDED` is sent; broker dispatch is skipped. |
| Produce exceeds the shared retained-memory log budget | `QUOTA_EXCEEDED` is sent before any part of that append is stored; fetching and acknowledging remain available to release capacity. |
| Broker storage failure | An `INTERNAL_ERROR` response is sent for built-in dispatch. |
| TLS socket timeout or blocking server read timeout | The connection is closed. |
| Acceptor fails while server is running | The server logs the accept failure and closes the server. |
| Server closes | The accept channel and active client channels are closed; the executor is shut down. |

Custom request handlers or processors that throw before encoding a response may still cause their connection to close. Do not rely on log text as a stable protocol contract.

## Security boundaries and operational notes

- TLS and mTLS are applied before request framing on the secure path; the broker handler does not receive an unauthenticated request through `SecureServerFactory`.
- Certificate trust-chain validation comes from the configured JSSE truststore and handshake. `CertificateValidator` performs Setu-specific certificate checks; it is not a trust-chain builder.
- `MtlsAuthenticator` derives identity from the certificate identity helper. Current certificate identity extraction supports DNS subject alternative names; it does not currently support URI SANs or common names.
- `TlsConfiguration` includes `keyAlias` and `protocol`, but the current key-manager provider does not select the alias and `TlsContextFactory` initializes a generic `TLS` context. The server/client apply non-empty enabled-protocol and enabled-cipher lists to their sockets.
- Plaintext construction is deliberately separate from `SecureServerFactory`. Do not accidentally wire a plaintext handler into an internet-facing listener.
- `InMemoryAuthorizer` stores exact grants only in the current process. Resource strings are `topic/<topic>`, `consumer-group/<group>`, and `broker`; there are no wildcard grants.
- `TcpServer` limits accepted active connections and can apply a blocking socket timeout. `SelectorTcpServer` applies its configured timeout as per-connection idle time. The selector transport is plaintext only.
- Request and response bodies are materialized as byte arrays. The frame limit bounds one frame, not total process memory across all clients.
- Group state and committed offsets are in-memory only. Partition records are forced to disk and rotated by size, but file logs have no checksum or retention.

## Testing and building

Use the Java 25 JDK required by the root project and run commands from the repository root:

```powershell
.\mvnw.cmd -pl setu-server -am test
.\mvnw.cmd -pl setu-server -am package
```

The `-am` option also builds required reactor modules such as `setu-core`, `setu-storage`, and `setu-security`. The Maven Wrapper supplies Maven but does not install Java. The TLS integration test uses the JDK `keytool` executable to create temporary test certificates, then verifies mTLS authentication, produce/fetch dispatch, and structured authorization denial. Tests also cover protocol validation, broker/log recovery, ACL matching, selector multiplexing/limits/timeouts. Broader coverage should still exercise invalid client certificates, quota rejection, malformed protocol bodies over a live connection, and shutdown under load.

## Planned server work

Remaining server milestones include:

1. Add protocol paging and explicit maximum-record-count controls for fetch/metadata responses.
2. Persist authorization grants, consumer offsets, and group state in the control plane.
3. Implement consumer-group partition assignment and rebalance.
4. Add selector-driven TLS using `SSLEngine`, or benchmark the current TLS virtual-thread transport before investing in it.
5. Add log checksums, retention, and configurable file durability/ack modes.
6. Add metrics for active/rejected connections, request rates, quota denials, and append/fetch latency.
7. Add broader integration tests for invalid client certificates, quota rejection, wire error cases, and server shutdown under load.
