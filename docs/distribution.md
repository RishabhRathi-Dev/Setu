# Setu binary distribution

This archive contains the Setu server JAR, its adjacent `lib/` dependencies, launchers, configuration, license and documentation. Install JDK 26 and set `JAVA_HOME`. Maven is not needed to run this distribution.

From the extracted archive's top-level directory:

```powershell
./scripts/start-broker.ps1 --config config/server.properties
```

On Linux/macOS:

```bash
bash scripts/start-broker.sh --config config/server.properties
```

The broker defaults to plaintext 127.0.0.1:9092 and persists data under `data/`. Set `operations.port=9093` in the properties file for local `/ready` and `/metrics`. Ctrl+C drains accepted storage work. Keep the server JAR beside its `lib/` directory, and retain the shared JVM options in `config/jvm.options`.

`SHA256SUMS` contains a digest for every packaged input file. The ZIP also has a SHA-256 sidecar. Compare that sidecar to a trusted workflow artifact before extracting. These are integrity hashes, not signatures.

FILE metadata checkpoints require the current generation-aware broker. Do not downgrade a checkpointed data directory. Stop the broker and back up the entire data directory before changing versions. The properties launcher does not configure TLS/mTLS; use the documented embedding API for secure listeners.

Source, runnable recovery examples, full API references and contribution instructions: [Setu repository](https://github.com/RishabhRathi-Dev/Setu).
