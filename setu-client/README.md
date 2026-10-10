# setu-client

`setu-client` currently provides `SetuClient`, a synchronous Java client for Setu protocol v1. It shares request/response and framing code from `setu-core`, and uses TLS configuration types from `setu-security`. The parent README summarizes the intended client APIs and current project-wide status in its [implementation snapshot](../README.md#current-implementation-snapshot).

## Implemented

- Plain TCP and TLS connection factories with configurable connect/read timeouts and maximum frame size.
- TLS protocol/cipher configuration, client-certificate support via the supplied `SSLContext` or `TlsConfiguration`/`TlsContextFactory`, and HTTPS endpoint identification for broker hostname verification.
- Synchronous request/response exchange with per-instance serialization, incrementing correlation IDs, response command/correlation validation, and structured `BrokerResponse` results.
- Convenience methods for `createTopic`, `produce`, `produceBatch`, `fetch`, `joinGroup`, `heartbeat`, `acknowledge`, `leaveGroup`, and `metadata`; other protocol commands can be sent through `request(BrokerRequest)`.
- `AutoCloseable` connection lifecycle.

## Example

```java
import io.setu.client.SetuClient;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.ErrorCode;

try (SetuClient client = SetuClient.connectPlain(
        "127.0.0.1", 9092, 5_000, 30_000, 16 * 1024 * 1024)) {
    BrokerResponse result = client.createTopic("orders", 4);
    if (result.errorCode() != ErrorCode.NONE) {
        throw new IllegalStateException(result.errorMessage());
    }
}
```

Use `connectTls(...)` for a TLS listener. The client must be configured with a truststore that trusts the broker certificate; for an mTLS listener its key material must also contain a client certificate trusted by the broker. Do not disable hostname verification in production. Protect keystore/truststore passwords and private keys through deployment secret handling, not source code.

## Request behavior

Each call writes one length-prefixed request body, reads one response body, decodes it, and verifies that the response command and correlation ID match. A broker-side protocol or application failure is returned as a `BrokerResponse` with an `ErrorCode`; it is not automatically thrown as an exception. Socket, framing, decoding, timeout, or correlation failures throw `IOException` (or a validation exception where applicable). The client does not reconnect after transport failure.

`produceBatch(topic, partition, values)` sends multiple records in one request and returns the first assigned offset. Records receive contiguous offsets in list order. Batches must be non-empty and fit within the protocol frame limit. File-backed storage forces each touched segment once before returning success.

Calls on one client object are serialized. There is no pipelining or concurrent in-flight request support. The synchronous client is suitable for protocol integration and simple use, not yet a high-throughput producer/consumer API. Group-aware `fetch` identifies the member, and `acknowledge` sends the next offset after records have been processed; in-memory storage uses these acknowledgements to reclaim records safely. Send `HEARTBEAT` while the member is active; the broker removes members that exceed its acknowledgement or inactivity deadline.

## Not implemented

- Dedicated high-level producer and consumer APIs, callbacks, futures, or reactive/async I/O.
- Retry policy, idempotent produce, delivery guarantees, compression, partition selection, and backpressure.
- Connection pooling, reconnect, failover, metadata refresh, authentication besides TLS client certificates, and metrics.
- Client-side protocol negotiation or compatibility with versions other than protocol v1.

## Build and integration tests

From the repository root with the project-required Java 25 JDK:

```powershell
.\mvnw.cmd -pl setu-client -am package
.\mvnw.cmd -pl setu-server -am test
```

The server TLS integration test exercises the client against a local TLS/mTLS broker path. The client module itself currently has no dedicated unit-test source tree.
