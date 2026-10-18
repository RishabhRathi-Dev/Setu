# setu-client

`SetuClient` is the synchronous Java client for Setu protocol v1. It supports plaintext and TLS/mTLS, shares codecs with [setu-core](../setu-core/README.md#protocol-v1), and uses [setu-security](../setu-security/README.md) for TLS configuration.

## Example

Start the [local broker](../README.md#quick-start), then run this in an application with the client module on its classpath. The example creates a new `orders` topic, appends a batch and fetches it by the returned offset.

```java
import io.setu.client.SetuClient;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ClientExample {
    public static void main(String[] args) throws Exception {
        try (var client = SetuClient.connectPlain("127.0.0.1", 9092,
                5_000, 30_000, 16 * 1024 * 1024)) {
            checked(client.createTopic("orders", 1));
            var appended = checked(client.produceBatch("orders", 0, List.of(
                    "order-1".getBytes(StandardCharsets.UTF_8),
                    "order-2".getBytes(StandardCharsets.UTF_8))));
            var page = checked(client.fetch("orders", 0, appended.offset(), 1024));
            for (var record : page.records()) {
                System.out.println(record.offset() + ": "
                        + new String(record.value(), StandardCharsets.UTF_8));
            }
        }
    }

    private static BrokerResponse checked(BrokerResponse response) throws IOException {
        if (response.errorCode() != ErrorCode.NONE) {
            throw new IOException(response.errorCode() + ": " + response.errorMessage());
        }
        return response;
    }
}
```

The connection factory arguments are host, port, connect timeout, read timeout and maximum frame-body size. Timeouts are milliseconds; zero disables the corresponding socket timeout. Close clients with try-with-resources.

## Requests and ownership

| Method | Result |
| --- | --- |
| `createTopic`, `metadata` | Create a topic or query partition counts |
| `produce`, `produceBatch` | Return the assigned offset or first batch offset |
| `fetch` | Return whole records from a starting offset within a byte budget |
| `joinGroup`, `heartbeat`, `leaveGroup` | Manage explicit membership |
| `acknowledge` | Commit the next offset after processing fetched records |
| `request` | Send a typed `BrokerRequest` directly |

Every request receives a correlation ID; responses must match both command and correlation. Broker denials return `BrokerResponse.errorCode()` rather than automatically throwing. Socket, framing, decoding and response-matching failures throw exceptions. The client does not reconnect after a transport failure.

Calls on one instance are serialized. `produceBatch` requires a nonempty batch within the [protocol limits](../setu-core/README.md#protocol-v1); offsets follow list order. Durable acknowledgements follow the broker's [force boundary](../setu-storage/README.md#durability-and-recovery).

Batch encoding copies caller bytes into the final wire frame. Fetched records share immutable slices of their response frame; `value()` returns a defensive copy. Keeping one slice can keep the complete frame alive. Copy values when retaining only a small subset for a long time.

Control replies reuse a private receive buffer capped at 64 KiB per client, released on close. Their decoded strings and metadata remain independent of later replies. Fetch replies retain privately owned frames so records remain valid across subsequent requests and client closure.

## TLS and consumer progress

`connectTls` accepts either an `SSLContext` plus protocol/cipher lists, or a `TlsConfiguration` and `TlsContextFactory`. It performs the handshake and verifies the broker hostname. For the [secure server example](../setu-server/README.md#secure-broker-wiring-example), connect to `localhost:9093` using `client.p12` and `client-trust.p12` from the development certificate scripts. The server trusts the client issuer and the client trusts the broker issuer.

For member-aware consumption:

1. Call `joinGroup(group, topic, member)` before producing records the member needs in memory mode; new memory members start at the current partition end.
2. Call `fetch(group, member, topic, partition, offset, maxBytes[, waitMillis])`.
3. Process the records, then call `acknowledge(group, member, topic, partition, nextOffset)` with the last processed offset plus one.
4. Send `heartbeat(group, member)` while idle, and `leaveGroup(group, member)` when done.

Long-poll waits are 0–30 seconds; the socket read timeout must exceed the wait. Memory storage reclaims according to registered members' progress; file storage retains history. Expired members must rejoin. See [broker semantics](../setu-core/README.md#broker-semantics).

There is no automatic retry, reconnect, partition routing, pooling, async/pipelined API or producer deduplication. A lost produce response can mean the write succeeded; blindly retrying can duplicate records. `QUOTA_EXCEEDED` indicates admission/policy rejection and may require backoff, a smaller batch or consumer progress.

## Build and verification

From the repository root with Java 26:

```powershell
.\mvnw.cmd -pl setu-client -am package
.\mvnw.cmd -pl setu-integration-tests -am test
```

Real TCP/mTLS tests and the server's [AOT training](../setu-server/README.md#standalone-jar) exercise client exchanges. The client module has no separate unit-test source tree. Embedding applications using Chronicle storage need the shared [JVM options](../config/jvm.options); an AOT cache belongs to the application's own entry point and classpath.
