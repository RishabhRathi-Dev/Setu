# setu-security

`setu-security` provides TLS context construction, certificate authentication, exact ACL authorization and per-principal quotas. It has no dependency on the broker protocol or storage engine. [setu-server](../setu-server/README.md#secure-broker-wiring-example) composes these components into the request path.

## Request flow

1. JSSE validates the peer's trust chain during the TLS handshake.
2. `MtlsAuthenticator` reads the established session's client certificate.
3. `CertificateValidator` checks validity dates and requires the client-auth extended key usage.
4. `CertificateIdentity` selects the first nonblank DNS SAN as the principal.
5. `InMemoryAuthorizer` checks the exact principal/resource/permission grant.
6. `QuotaManager` checks the principal's message and byte buckets before dispatch.

A valid certificate grants no permissions by itself. Authentication failures stop the session; authorization and quota failures become protocol errors in the server. The standalone properties launcher is plaintext and does not configure this path.

## TLS and identity

| Component | Responsibility |
| --- | --- |
| `TlsConfiguration` | Keystore/truststore locations and passwords, client-auth requirement, enabled protocols and cipher suites |
| `SetuKeyManagerProvider` / `SetuTrustManagerProvider` | Load key and trust material into JSSE managers |
| `TlsContextFactory` | Initialize an `SSLContext` with those managers |
| `CertificateValidator` | Check client certificate dates and explicit `clientAuth` EKU |
| `CertificateIdentity` | Extract a DNS SAN principal |
| `MtlsAuthenticator` | Authenticate an established `SSLSession` |

The context factory initializes a generic `TLS` context. The server/client applies enabled protocol and cipher lists to sockets. `keyAlias` is present in the configuration but is not selected by the key-manager provider. Certificate chain validation belongs to JSSE, not `CertificateValidator`; URI SANs and subject common names are not identity fallbacks.

`TlsConfiguration` stores passwords as strings. Provision them through application configuration and keep keystores/passwords out of version control. Certificate issuing, revocation checking, automatic rotation and credential-provider integrations are not implemented.

## Authorization and quotas

```java
import io.setu.security.authorization.InMemoryAuthorizer;
import io.setu.security.authorization.Permission;
import io.setu.security.quota.QuotaManager;
import io.setu.security.quota.QuotaPolicy;
import io.setu.security.quota.TokenBucketRateLimiter;

var acl = new InMemoryAuthorizer();
acl.grant("orders-service", "topic/orders", Permission.PRODUCE);
acl.grant("orders-service", "broker", Permission.VIEW_METADATA);

var limiter = new TokenBucketRateLimiter();
limiter.registerPolicy("orders-service", new QuotaPolicy(
        1_000, 2_000,             // requests per second and request burst
        10_000_000, 20_000_000)); // bytes per second and byte burst
var quotas = new QuotaManager(limiter);
```

`InMemoryAuthorizer` denies unless the authenticated principal matches the context and an exact grant exists. `revoke` removes a grant. Resource strings are literal: `topic/orders` does not match `topic/orders-*`. Role values do not automatically expand into ACL registrations. The server owns the [command-to-permission mapping](../setu-server/README.md#request-authorization-contract).

`QuotaPolicy` takes four positive values: message refill rate, message burst, byte refill rate and byte burst. Each burst must be at least its refill rate. Both buckets are checked and consumed atomically; rejection consumes neither. Unregistered principals are denied. In the built-in server, one protocol request counts as one message, including an entire produce batch, and bytes count its request body.

Grants and quota policies are process-local. There is no persistence, wildcard matching, policy reload service, per-topic quota selection or distributed rate limit.

## Integration and tests

Use `SecureServerFactory.createBroker` with a `TlsConfiguration`, `TlsContextFactory`, authenticator, authorizer, quota manager and broker. The [server example](../setu-server/README.md#secure-broker-wiring-example) shows the complete lifecycle; [SetuClient](../setu-client/README.md#tls-and-consumer-progress) handles client TLS and hostname verification.

From the repository root with Java 26:

```powershell
.\mvnw.cmd -pl setu-security test
.\mvnw.cmd -pl setu-integration-tests -am '-Dtest=*IntegrationTests' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Unit tests cover certificate validation/identity, ACL decisions, quotas and TLS material/context loading. Integration and [AOT training](../setu-server/README.md#standalone-jar) exercise real mTLS, missing-certificate rejection, ACL/quota denials and both storage modes. A `certificate_required` alert during the deliberate missing-certificate check is expected.
