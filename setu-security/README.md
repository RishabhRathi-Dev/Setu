# Setu Security

`setu-security` is the security subsystem of **Setu**, a distributed message broker written in modern Java.

The module provides certificate-based client authentication, TLS configuration, authorization, and resource quotas while remaining independent of Setu's storage, networking, and message-processing implementations.

The central design principle is:

> **Authentication establishes identity. Authorization establishes permissions. Quotas control resource consumption.**

## Implementation status

The module provides security interfaces and implementations that are integrated by `setu-server` at the TLS connection and request-processing boundaries. The server includes a protocol metadata resolver and this module provides a default-deny in-memory ACL authorizer. There is no persistent policy store, wildcard ACL matching, dynamic reload, or cluster-wide policy replication. The current implementation contains:

* `TlsConfiguration`, keystore/truststore-backed JSSE manager providers, and an `SSLContext` factory. The factory initializes the managers and context; `setu-server` owns socket setup and applies protocol/cipher settings and required client authentication. `keyAlias` is not currently selected by the key-manager provider.
* `CertificateValidator`, which checks certificate validity and requires the TLS client-auth extended key usage. Trust-chain validation is expected to have happened during the TLS handshake; this validator does not build or verify chains.
* `CertificateIdentity`, which uses the first nonblank DNS subject alternative name as the principal. It does not currently support URI SANs or subject common names.
* `MtlsAuthenticator`, which reads the peer certificate from an established `SSLSession`, validates it, and returns that principal.
* Permission/role value types and interfaces for `AccessPolicy` and `Authorizer`, plus `InMemoryAuthorizer`, which stores exact principal/resource/permission grants and denies by default. Grants are process-local.
* A per-principal token-bucket limiter for message and byte counts, plus `QuotaManager`. The server checks quotas after authorization and before broker dispatch. Quotas are not resource-specific or distributed.

There are no credential-provider classes in the source tree. Certificate issuing, revocation, persistent policy management, and distributed quotas remain future work. `setu-server` owns socket configuration, server-side TLS integration, and the TLS-enabled client path; an end-to-end mTLS broker test exercises certificate identity, produce/fetch dispatch, and authorization denial. Sections below that describe other capabilities are design guidance, not claims about shipped behavior.

## Server integration

On the secure server path, TLS completes before any protocol frame is read. The TLS socket requires a client certificate; the configured truststore validates its trust chain, and `MtlsAuthenticator` validates the client-auth EKU and extracts the first DNS SAN as the principal. The Setu request path then validates the v1 protocol body, resolves its resource and permission, calls `Authorizer`, checks the principal's message/byte quota, and finally dispatches to the broker. A failed authentication prevents the protocol session from starting; authorization and quota denials are encoded as protocol error responses and do not reach the broker operation.

`InMemoryAuthorizer` is the built-in implementation. It matches grants by the exact `(principal, resource, permission)` tuple, rejects identity/context principal mismatches, and denies all ungranted operations. For example, grant `orders-service` the permission `PRODUCE` on `topic/orders` and `VIEW_METADATA` on `broker`. Grants are local to one process, and role values are not automatically expanded into ACL grants.

The server-side `ProtocolRequestAuthorizationMetadataResolver` owns the v1 command mapping. This keeps broker command parsing out of `setu-security`; the security module consumes the resulting identity, resource, permission, and usage counts through its interfaces.

The packaged application's [AOT training workload](../setu-server/README.md#standalone-jar) covers this complete secure path using the real TLS client and both storage modes. Training verifies valid mTLS identity, missing-certificate rejection, ACL allow/deny, and quota allow/deny with disposable certificates. The resulting application cache contains trained security classes alongside server, client, core, and storage; the cached application runs with ZGC and string deduplication. Functional security tests do not need AOT caches.

---

## Goals

The security module is designed to provide:

* Mutual TLS (mTLS) for client authentication
* X.509 certificate validation and identity extraction
* Clear separation between authentication and authorization
* Role- and permission-based access control
* Resource-level authorization for topics and consumer groups
* Rate and bandwidth quotas for clients
* Pluggable credential management
* TLS configuration independent of the broker implementation
* Explicit security boundaries between `setu-security` and the rest of Setu
* Support for certificate rotation without coupling security to the message-storage layer

---

## Non-Goals

`setu-security` is **not** intended to become a general-purpose identity-management platform.

The module does not initially provide:

* JWT/OIDC authentication
* API-key authentication
* OAuth authorization
* User registration
* Identity-provider functionality
* A certificate authority (CA)
* Certificate issuance
* Persistent user/account management
* A policy language
* Broker message encryption
* Message-level cryptographic signatures

Setu acts as a **security consumer**. Certificates are assumed to be issued by a trusted CA or certificate-management system outside the broker.

---

# Architecture

The high-level security flow is:

```text
                         ┌─────────────────────┐
                         │      Setu Client    │
                         │                     │
                         │ Client Certificate  │
                         └──────────┬──────────┘
                                    │
                                    │ TLS 1.3 / mTLS
                                    ▼
                         ┌─────────────────────┐
                         │    TLS Layer        │
                         │                     │
                         │ KeyManager          │
                         │ TrustManager        │
                         └──────────┬──────────┘
                                    │
                              Valid certificate
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │ CertificateValidator│
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │ CertificateIdentity │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │ MtlsAuthenticator   │
                         └──────────┬──────────┘
                                    │
                         AuthenticationResult
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │     Authorizer      │
                         │                     │
                         │ Role / Permission  │
                         │ AccessPolicy        │
                         └──────────┬──────────┘
                                    │
                                  ALLOW
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │    QuotaManager     │
                         │                     │
                         │ Rate / Byte Limits │
                         └──────────┬──────────┘
                                    │
                                  ALLOW
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │     Setu Broker     │
                         │                     │
                         │ Produce / Consume   │
                         └─────────────────────┘
```

The security module therefore follows three major stages:

```text
        WHO ARE YOU?
             │
             ▼
       Authentication
             │
             ▼
        CAN YOU DO IT?
             │
             ▼
        Authorization
             │
             ▼
        HOW MUCH?
             │
             ▼
           Quota
```

---

# Module Structure

```text
setu-security/
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
    │   ├── SetuKeyManagerProvider.java
    │   ├── SetuTrustManagerProvider.java
    │   ├── TlsContextFactory.java
    │   └── TlsConfigurationException.java
    │
    ├── certificates/
    │   ├── CertificateValidator.java
    │   ├── CertificateIdentity.java
    │   └── CertificateValidationException.java
    │
    └── quota/
        ├── RateLimiter.java
        ├── QuotaManager.java
        ├── QuotaPolicy.java
        ├── QuotaDecision.java
        ├── TokenBucket.java
        └── TokenBucketRateLimiter.java
    │
    └── test/java/
```

---

# 1. Authentication

The authentication layer establishes the identity of a Setu client.

The initial implementation uses **mutual TLS (mTLS)**.

A successful authentication should produce an explicit identity rather than merely returning `true` or `false`.

---

## `AuthenticationProvider.java`

### Goal

Define the common authentication contract.

The rest of Setu should not need to know how authentication is performed.

Conceptually:

```java
public interface AuthenticationProvider {

    AuthenticationResult authenticate(
        AuthenticationRequest request
    );

    AuthenticationMethod method();
}
```

### Responsibilities

* Define the authentication interface
* Accept authentication material
* Return an authenticated identity
* Report the authentication mechanism used

### Should NOT

* Perform authorization
* Check topic permissions
* Apply quotas
* Know about message storage
* Know about `MemorySegment`
* Know about PostgreSQL

### Design rationale

This abstraction allows Setu to add another authentication mechanism in the future without changing the authorization layer.

For example:

```text
AuthenticationProvider
        │
        └── MtlsAuthenticator
```

The authorization layer remains unchanged.

---

# 2. `AuthenticationRequest.java`

### Goal

Represent the information required to authenticate a client.

For mTLS, this may contain information obtained from the established TLS session.

Conceptually:

```java
public record AuthenticationRequest(
    SSLSession sslSession
) {}
```

### Responsibilities

* Carry authentication input
* Provide a stable interface between the network layer and authentication layer

### Should NOT

* Validate the certificate itself
* Decide permissions
* Contain broker-specific operations

The network layer constructs the request and passes it to the authentication provider.

---

# 3. `AuthenticationResult.java`

### Goal

Represent the result of successful authentication.

This is one of the most important objects in the security architecture.

A boolean:

```text
authenticated = true
```

is insufficient because the broker needs to know **who** was authenticated.

Conceptually:

```java
public record AuthenticationResult(
    String principal,
    AuthenticationMethod method,
    Map<String, Object> attributes
) {}
```

Example:

```text
principal = payments-service
method    = MTLS

attributes:
    commonName = payments-service
    organization = payments
    certificateSerial = ...
```

### Responsibilities

* Represent the authenticated principal
* Record authentication method
* Carry relevant identity attributes
* Provide identity to authorization

### Should NOT

* Contain authorization decisions
* Contain quota state
* Contain private key material

---

# 4. `AuthenticationMethod.java`

### Goal

Identify which authentication mechanism established the identity.

Initial implementation:

```java
public enum AuthenticationMethod {
    MTLS
}
```

The enum can later expand without changing `AuthenticationResult`.

---

# 5. `AuthenticationException.java`

### Goal

Represent authentication failures explicitly.

Examples:

```text
Missing certificate
Invalid certificate
Expired certificate
Untrusted certificate
Malformed certificate
No valid identity
```

Authentication failures should be distinguishable from authorization failures.

For example:

```text
401-style failure
    ≠
403-style failure
```

The exact network protocol representation is handled outside this module.

---

# 6. `MtlsAuthenticator.java`

### Goal

Authenticate Setu clients using their X.509 client certificates.

The high-level flow is:

```text
TLS session
    │
    ▼
Client certificate
    │
    ▼
CertificateValidator
    │
    ▼
CertificateIdentity
    │
    ▼
AuthenticationResult
```

### Responsibilities

* Obtain client certificate information from the TLS session
* Invoke certificate validation
* Extract the client identity
* Construct `AuthenticationResult`

### Should NOT

* Construct the TLS `SSLContext`
* Manage private keys
* Decide topic permissions
* Enforce quotas

The authenticator establishes:

> "This connection represents principal X."

It does not establish:

> "Principal X can produce to topic Y."

That is authorization.

---

# 7. TLS

The TLS package handles cryptographic transport configuration.

TLS is deliberately separated from authentication.

The TLS layer establishes a secure channel and validates the certificate chain.

The authentication layer converts the validated certificate into a Setu identity.

---

# 8. `TlsConfiguration.java`

### Goal

Represent all TLS configuration required by Setu.

Possible configuration:

```text
enabled
protocol
keystore
truststore
clientAuthentication
key aliases
supported protocols
supported cipher suites
```

Example conceptual configuration:

```text
TLS:
    protocol = TLSv1.3
    clientAuth = REQUIRED

Server:
    certificate = server certificate
    privateKey = server private key

Trust:
    CA = Setu trusted CA
```

### Responsibilities

* Represent TLS configuration
* Provide validated configuration to TLS initialization
* Keep TLS configuration independent of networking implementation

### Should NOT

* Authenticate application-level identities
* Authorize topic operations

---

# 9. `KeyManagerFactory.java`

### Goal

Create/configure the JSSE `KeyManager` required by Setu's TLS context.

Conceptually:

```text
Private Key
     +
Server Certificate
     │
     ▼
KeyManager
     │
     ▼
SSLContext
```

### Responsibilities

* Load server key material
* Construct key managers
* Configure the appropriate key aliases where necessary

### Should NOT

* Validate client authorization
* Manage broker permissions

---

# 10. `TrustManagerFactory.java`

### Goal

Create/configure trust managers used to validate certificates.

For mTLS:

```text
Client Certificate
        │
        ▼
TrustManager
        │
        ▼
Trusted CA?
        │
    ┌───┴───┐
   YES      NO
    │        │
 accept    reject
```

### Responsibilities

* Load trusted CA material
* Construct trust managers
* Configure certificate trust validation

### Should NOT

* Determine Setu roles
* Determine topic permissions

---

# 11. Certificates

The certificate package is concerned with certificate-specific validation and identity extraction.

This is separate from the lower-level TLS configuration.

---

# 12. `CertificateValidator.java`

### Goal

Perform Setu-specific certificate validation beyond basic TLS setup where required.

Checks performed by the current implementation:

```text
Certificate exists
        ↓
TLS handshake has verified the certificate chain
        ↓
Certificate is within validity period
        ↓
TLS client-auth extended key usage is present
        ↓
Required identity fields are present
```

### Responsibilities

* Validate certificate properties
* Reject certificates that don't satisfy Setu requirements
* Extract or validate the fields required to establish identity

### Should NOT

* Decide whether the principal can produce
* Decide whether the principal can consume
* Apply rate limits

---

# 13. `CertificateIdentity.java`

### Goal

Represent the logical identity extracted from an X.509 certificate.

Conceptually:

```java
public record CertificateIdentity(
    String subject,
    String commonName,
    Set<String> organizations,
    String serialNumber
) {}
```

For example:

```text
Certificate:

CN=payments-service
O=payments
```

could produce:

```text
CertificateIdentity:

commonName   = payments-service
organization = payments
```

### Important design principle

The **logical principal** should be based on a stable certificate identity field such as an appropriate SAN/subject identity.

The certificate serial number is useful for:

* auditing
* certificate lifecycle tracking
* revocation
* debugging

but should not automatically become the logical user/service identity.

---

# 14. Authorization

Authentication establishes:

```text
payments-service
```

Authorization establishes:

```text
payments-service
    CAN PRODUCE
    TO topic/payments
```

The authorization layer should be independent of the authentication mechanism.

---

# 15. `Authorizer.java`

### Goal

Provide the main authorization decision point.

Conceptually:

```java
public interface Authorizer {

    AuthorizationDecision authorize(
        AuthenticationResult identity,
        AuthorizationContext context
    );
}
```

The broker should interact primarily with this abstraction.

### Responsibilities

* Receive authenticated identity
* Evaluate the requested operation
* Consult applicable access policies
* Return an authorization decision

### Should NOT

* Parse certificates
* Validate TLS
* Manage credentials
* Implement message storage

---

# 16. `AuthorizationContext.java`

### Goal

Represent the security context of an operation.

Authorization in a message broker is resource-specific.

It is not enough to ask:

```text
Can payments-service PRODUCE?
```

The real question is:

```text
Can payments-service PRODUCE
to topic/payments?
```

Conceptually:

```java
public record AuthorizationContext(
    String principal,
    String resource,
    Permission permission
) {}
```

Example:

```text
principal  = payments-service
resource   = topic/payments
permission = PRODUCE
```

The model can later be expanded to include:

```text
resourceType
consumerGroup
authenticationMethod
clientAddress
```

without changing the fundamental authorization architecture.

---

# 17. `AccessPolicy.java`

### Goal

Represent the actual access-control rules.

Example:

```text
payments-service
    PRODUCE → topic/payments.*

analytics-service
    CONSUME → topic/payments.*

monitoring-service
    CONSUME → topic/metrics.*
```

### Responsibilities

* Represent principal/resource/permission relationships
* Support matching against authorization requests
* Express allow/deny rules

### Should NOT

* Perform certificate validation
* Manage TLS
* Enforce network rate limits

---

# 18. `Permission.java`

### Goal

Define the operations that Setu protects.

The initial permission model should be broker-specific.

Example:

```java
public enum Permission {

    TOPIC_CREATE,
    TOPIC_DELETE,

    PRODUCE,
    CONSUME,

    CONSUMER_GROUP_CREATE,
    CONSUMER_GROUP_READ,
    CONSUMER_GROUP_WRITE,
    VIEW_METADATA,

    ACL_READ,
    ACL_WRITE
}
```

The goal is to avoid overly generic permissions such as:

```text
READ
WRITE
ADMIN
```

because Setu needs resource-specific operations.

---

# 19. `Role.java`

### Goal

Group permissions into reusable roles.

Example:

```text
PRODUCER
    └── PRODUCE

CONSUMER
    └── CONSUME

OPERATOR
    ├── TOPIC_CREATE
    ├── TOPIC_DELETE
    ├── PRODUCE
    └── CONSUME

ADMIN
    └── administrative permissions
```

Roles are convenience abstractions over permissions.

They should not replace resource-level access policies.

For example:

```text
Role:
    PRODUCER

Policy:
    PRODUCER → PRODUCE → topic/payments.*
```

---

# 20. Quotas

Authorization answers:

> Is this operation allowed?

Quota answers:

> Is this client currently within its resource limits?

This distinction is important for a distributed message broker.

A client may be authorized to produce but still be throttled because it exceeded its configured throughput.

---

# 21. `RateLimiter.java`

### Goal

Enforce operation or throughput limits.

For Setu, rate limiting should not be limited to HTTP-style:

```text
requests / second
```

Useful broker metrics include:

```text
messages / second
bytes / second
```

Example:

```text
producer-A

10,000 messages/sec
100 MB/sec
```

### Responsibilities

* Determine whether an operation is currently permitted
* Track consumption against a configured limit
* Support throttling/rejection behavior

### Potential implementation

A token-bucket implementation would be a reasonable first implementation.

The exact algorithm should remain behind the interface.

---

# 22. `QuotaManager.java`

### Goal

Manage quotas at a higher level than the raw rate limiter.

`RateLimiter` answers:

```text
Can this operation proceed now?
```

The current `QuotaManager` delegates principal/message/byte counts to a configured `RateLimiter`; it does not select a quota by resource.

The future resource-aware design could answer:

```text
Which quota applies to this principal/resource?
```

Possible future configuration:

```text
producer-A
    topic/payments
        max = 100 MB/s

producer-A
    topic/logs
        max = 20 MB/s
```

### Responsibilities

* Resolve applicable quota policies
* Coordinate with `RateLimiter`
* Apply principal/resource-specific limits
* Provide a single quota interface to the broker

---

# 23. `QuotaPolicy.java`

### Goal

Represent configured resource limits.

Conceptually:

```java
public record QuotaPolicy(
    long maxMessagesPerSecond,
    long maxBytesPerSecond
) {}
```

The model can eventually become resource-specific:

```text
Principal
     +
Resource
     +
QuotaPolicy
```

This allows different limits for different topics or client classes.

---

# 24. Credential-provider roadmap

Credential providers are not implemented.

It intentionally does not dictate where credentials come from.

---

# 25. CredentialProvider roadmap

### Goal

Provide credentials required by the security subsystem without coupling authentication code to a particular secret-management system.

Potential implementations:

```text
CredentialProvider
       │
       ├── FileCredentialProvider
       ├── EnvironmentCredentialProvider
       └── VaultCredentialProvider
```

### Responsibilities

* Retrieve required credential material
* Abstract credential storage
* Provide credentials to TLS/security components

### Should NOT

* Implement authorization
* Store credentials in application logs
* Expose secrets unnecessarily

---

# 26. Credential model roadmap

### Goal

Represent credential metadata and controlled access to credential material.

Potential information:

```text
credential type
credential identifier
version
metadata
secret material
```

Sensitive material should have a deliberately limited lifecycle and should never be logged.

For Setu's first implementation, this class can remain intentionally minimal.

---

# Security Flow

## Connection establishment

A Setu client connects using mTLS:

```text
Client
   │
   │ TLS ClientHello
   ▼
Setu
   │
   │ requests client certificate
   ▼
Client Certificate
   │
   ▼
TrustManager
   │
   ├── trusted CA?
   ├── valid chain?
   └── valid certificate?
   │
   ▼
TLS handshake succeeds
   │
   ▼
MtlsAuthenticator
   │
   ▼
CertificateValidator
   │
   ▼
CertificateIdentity
   │
   ▼
AuthenticationResult
```

At this point Setu knows:

```text
WHO:
    payments-service
```

---

# Authorization Flow

Suppose the client attempts:

```text
PRODUCE
topic = payments
```

Setu creates:

```text
AuthorizationContext

principal  = payments-service
resource   = topic/payments
permission = PRODUCE
```

Then:

```text
AuthenticationResult
        │
        ▼
     Authorizer
        │
        ▼
   AccessPolicy
        │
        ▼
 ALLOW / DENY
```

If denied:

```text
Client
  │
  ▼
Authorization failure
```

If allowed:

```text
Client
  │
  ▼
QuotaManager
```

---

# Quota Flow

After authorization:

```text
Authorized operation
        │
        ▼
   QuotaManager
        │
        ▼
   QuotaPolicy
        │
        ▼
   RateLimiter
        │
     ┌──┴──┐
    ALLOW  LIMIT
      │      │
      ▼      ▼
   Produce  Throttle/
            Reject
```

This prevents an authorized client from consuming unlimited broker resources.

---

# Dependency Boundaries

A critical architectural goal is that `setu-security` must **not depend on Setu's storage engine**.

It should not know about:

```text
MemorySegment
MappedByteBuffer
LogSegment
MessageStore
PostgreSQL
Producer
Consumer
Partition
```

Instead:

```text
                    setu-security
                         │
              security abstractions
                         │
             ┌───────────┴───────────┐
             ▼                       ▼
       setu-network             setu-core
             │                       │
             └───────────┬───────────┘
                         ▼
                     Setu Broker
```

The security module provides capabilities; the broker decides where to invoke them.

---

# Example Broker Interaction

The broker should conceptually interact with security like this:

```java
AuthenticationResult identity =
        authenticationProvider.authenticate(request);

authorizer.authorize(
        identity,
        new AuthorizationContext(
            identity.principal(),
            "topic/payments",
            Permission.PRODUCE
        )
);

quotaManager.check(
        identity.principal(),
        "topic/payments",
        messageSize
);

broker.produce(message);
```

The important part is that:

```text
broker
  │
  ├── does not validate certificates
  ├── does not parse X.509 fields
  ├── does not implement ACL matching
  └── does not implement token-bucket logic
```

Those responsibilities belong to `setu-security`.

---

# Security Invariants

The implementation should maintain several explicit invariants.

### 1. No unauthenticated client reaches authorization

```text
No AuthenticationResult
        ↓
No authorization
        ↓
No broker operation
```

### 2. Authentication does not imply authorization

A valid certificate means:

```text
"This identity is authentic."
```

It does not mean:

```text
"This identity can perform every operation."
```

### 3. Authorization does not imply unlimited access

A client can be authorized but throttled.

```text
AUTHORIZED ≠ UNLIMITED
```

### 4. Private keys never enter authorization

Authorization should operate on identity, not cryptographic secrets.

### 5. Security does not depend on storage

The storage implementation can change without requiring a redesign of the security model.

---

# Certificate Identity Model

A certificate should establish a stable logical principal.

For example:

```text
Certificate Subject/SAN
        │
        ▼
payments-service
        │
        ▼
Setu Principal
```

Certificate-specific metadata such as:

```text
serial number
issuer
validity period
fingerprint
```

can be retained for auditing and certificate lifecycle operations but should not automatically define the application identity.

---

# Certificate Rotation

The design should allow certificates to be rotated without changing the authorization model.

For example:

```text
Old Certificate
    │
    │ principal = payments-service
    ▼
Authorization policies

       ↓ rotation

New Certificate
    │
    │ principal = payments-service
    ▼
Same authorization policies
```

The certificate changes, but the logical service identity remains the same.

This is one reason identity extraction and authorization policy should be separate components.

---

# Failure Handling

Security failures should fail closed.

Examples:

```text
Invalid certificate
        → reject connection

Expired certificate
        → reject connection

Unknown identity
        → reject authentication

No matching authorization policy
        → deny operation

Quota exceeded
        → throttle/reject according to quota policy
```

A security component should never interpret an error such as:

```text
policy unavailable
certificate validation unavailable
credential unavailable
```

as permission to proceed.

---

# Testing Strategy

Each layer should be testable independently.

## Authentication tests

Test:

```text
Valid certificate
Expired certificate
Untrusted CA
Malformed certificate
Missing certificate
Invalid identity
```

## Authorization tests

Test:

```text
Role-to-permission behavior, authorization-context validation, and `InMemoryAuthorizer` exact-grant/default-deny behavior are unit tested. The server TLS integration test also exercises an authenticated allow path and a structured authorization denial.
```

## Quota tests

Test:

```text
Within limit → ALLOW

At limit → defined boundary behavior

Over limit → THROTTLE/REJECT

Different principals → independent limits

Resource-specific limits are not implemented.
```

## TLS tests

Test:

```text
Keystore and truststore loading and SSLContext initialization are unit tested. A server/client mTLS integration test uses temporary keytool-generated certificates and exercises client identity, broker produce/fetch, and authorization denial. Additional cases for invalid trust chains, absent client certificates, and protocol/cipher rejection remain to be added.
```

The selector-based plaintext transport does not use these TLS components; TLS currently uses the virtual-thread server transport.

---

# Implementation Order

The recommended implementation order is:

```text
1. TLS configuration
        │
        ▼
2. Certificate validation
        │
        ▼
3. Certificate identity
        │
        ▼
4. mTLS authentication
        │
        ▼
5. AuthenticationResult
        │
        ▼
6. Permission / Role
        │
        ▼
7. AccessPolicy
        │
        ▼
8. Authorizer
        │
        ▼
9. QuotaPolicy
        │
        ▼
10. RateLimiter
        │
        ▼
11. QuotaManager
```

Do not start with quotas or elaborate ACLs before the certificate-based identity model is working.

---

# Future Extensions

The architecture intentionally leaves room for future capabilities.

Potential future authentication mechanisms:

```text
JwtAuthenticator
ApiKeyAuthenticator
```

Potential credential providers:

```text
VaultCredentialProvider
CloudCredentialProvider
```

Potential authorization enhancements:

```text
ACL persistence
Dynamic policy reload
Consumer-group permissions
Topic-pattern permissions
Audit logging
```

Potential quota enhancements:

```text
Distributed quotas
Cluster-wide rate limiting
Per-topic quotas
Per-consumer-group quotas
Adaptive throttling
```

These should be additions behind existing abstractions rather than reasons to redesign the current module.

---

# Design Summary

`setu-security` is deliberately built around three independent responsibilities:

```text
┌──────────────────────────────────────────┐
│              AUTHENTICATION              │
│                                          │
│ Certificate → Identity                   │
└────────────────────┬─────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────┐
│               AUTHORIZATION              │
│                                          │
│ Identity + Resource + Permission         │
│                  ↓                       │
│              ALLOW / DENY                │
└────────────────────┬─────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────┐
│                  QUOTA                   │
│                                          │
│ Identity + Resource + Usage              │
│                  ↓                       │
│           ALLOW / THROTTLE               │
└────────────────────┬─────────────────────┘
                     │
                     ▼
                SETU BROKER
```

The resulting separation is:

```text
TLS
 ↓
Certificate
 ↓
Authentication
 ↓
Identity
 ↓
Authorization
 ↓
Quota
 ↓
Broker Operation
```

The module's primary architectural objective is **not to implement every possible security feature**. It is to establish a clean, extensible security boundary around Setu so that the broker can evolve independently of its authentication mechanism, authorization implementation, and resource-control strategy.
