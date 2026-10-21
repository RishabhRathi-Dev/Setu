# setu-integration-tests

This module verifies broker behavior through public client/server APIs over real TCP and mTLS. These are controlled functional checks; saturation, process-kill and capacity workloads belong to [setu-benchmark](../setu-benchmark/README.md).

## Coverage

| Area | Verified behavior |
| --- | --- |
| Storage modes | Produce/fetch in both modes, full Chronicle history and immutable batch snapshots |
| Consumer progress | Join-time memory offsets and acknowledgement-based reclamation |
| Recovery | File memberships and only acknowledged progress survive restart; leave removes saved state |
| Async client | Concurrent pooled writes, keyed routing, payload ownership, bounded queues, close, read reconnects and no uncertain-write retries |
| PostgreSQL | Optional database tests for outbox rollback, relay redelivery and atomic inbox/business effects; CI provides PostgreSQL |
| Backpressure | Whole-batch memory admission, quota rejection, reclamation and successful retry |
| Security | Real mTLS, certificate identity, authenticated broker requests and ACL denial |

See [broker semantics](../setu-core/README.md#broker-semantics), [storage contracts](../setu-storage/README.md) and [secure wiring](../setu-server/README.md#secure-broker-wiring-example) for the behavior under test.

## Run

From the repository root with Java 26 on `JAVA_HOME` and `PATH`:

```powershell
.\mvnw.cmd -pl setu-integration-tests -am test
```

This also builds and tests required production modules. To select only the integration classes:

```powershell
.\mvnw.cmd -pl setu-integration-tests -am '-Dtest=*IntegrationTests' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

On Linux/macOS, use `bash ./mvnw` instead of `.\mvnw.cmd`. Maven applies the shared [JVM options](../config/jvm.options); AOT caches are not required.

Tests bind loopback sockets on ephemeral ports. TLS fixtures are short-lived PKCS#12 stores/certificates generated with the JDK `keytool` under temporary directories. No certificate material is checked in. For manual development stores, use [generate-certs.ps1](../scripts/generate-certs.ps1) or [generate-certs.sh](../scripts/generate-certs.sh).

Surefire reports are written under `setu-integration-tests/target/surefire-reports/`. [CI](../.github/workflows/ci.yml) runs integration tests after production unit checks and retains their reports.

Client failure coverage uses real sockets to check blocked read/write cancellation, timeout/partial/malformed/mismatched replies invalidating the connection, and reuse after structured broker errors. TLS coverage checks missing-client-certificate rejection, client hostname verification, stalled handshakes and frames, plus both topic and group permission for member-aware fetches.
