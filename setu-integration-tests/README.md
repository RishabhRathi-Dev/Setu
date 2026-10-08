# Setu integration tests

This module verifies application requirements through public client and server APIs across real TCP connections. It covers broker behavior in both storage modes, acknowledgement-based in-memory reclamation, file-history reads, join-time offsets for new in-memory members, and mTLS authentication and authorization. These tests validate expected behavior at controlled scale; they do not try to saturate or exhaust the broker.

Run it from the repository root with Java 25:

```powershell
.\mvnw.cmd -pl setu-integration-tests -am '-Dtest=*IntegrationTests' -Dsurefire.failIfNoSpecifiedTests=false test
```

The mTLS test creates short-lived PKCS#12 stores and certificates under JUnit's temporary directory, starts a broker and server on an ephemeral port, and verifies authenticated produce/fetch plus authorization denial. It leaves no certificate material in the repository. Failure injection, broker crashes, stress, and capacity exhaustion live in `setu-benchmark/src/test/java/io/setu/benchmark/chaos`.

