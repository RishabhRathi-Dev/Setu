# Contributing

Setu uses a Maven reactor with seven modules and JDK 26. Start with the [README](README.md), [architecture](docs/architecture.md), and the README for the module you are changing.

## Development setup

1. Install JDK 26 and set `JAVA_HOME` and `PATH`.
2. Use the checked-in Maven Wrapper; it pins Maven in `.mvn/wrapper/maven-wrapper.properties`.
3. Install Python 3.10+ for benchmark tooling and report tests; they use the standard library.
4. Import the root `pom.xml` into your IDE. IDE settings and generated artifacts remain local.

Ordinary build and functional checks run on Windows, Linux, and macOS. Resource-enforced benchmark profiles require Linux/WSL with systemd and cgroup v2. All commands below run from the repository root; substitute `bash ./mvnw` for `.\mvnw.cmd` on Linux/macOS.

## Verification

| Change | Command / guide |
| --- | --- |
| Production behavior or API | `./mvnw.cmd -pl setu-integration-tests -am test` |
| Benchmark helpers, excluding chaos/load entry points | `./mvnw.cmd -pl setu-benchmark -am '-Dtest=*Tests,!*ChaosTests,!*BenchmarkTests' '-Dsurefire.failIfNoSpecifiedTests=false' test` |
| Python launchers, reports, or publication | `python -m unittest discover -s scripts -p 'test_*.py'` |
| Standalone packaging | `./mvnw.cmd -pl setu-server -am package` and the [quickstart](README.md#quick-start) |
| Security integration | [TCP/mTLS integration tests](setu-integration-tests/README.md) |
| Performance or durability | [Benchmark profiles and evidence requirements](setu-benchmark/README.md) |

Use `-am` when building a module with reactor dependencies. The full root reactor includes stress and benchmark workloads; choose the explicit suites above for ordinary development. Maven loads the shared `config/jvm.options`. Surefire reports are written under each module's `target/surefire-reports/`.

CI also exercises bounded chaos, isolated load, baseline/target measurements, and secure AOT training. Deliberate missing-client-certificate tests may log `certificate_required`. Benchmark expectation misses warn by default while remaining unqualified in reports; `--strict` enforces performance expectations. Crashes, invalid captures, request errors, and zero progress remain execution failures.

## Implementation conventions

- Keep transport/policy in server, broker semantics and protocol in core, and log/durability behavior in storage. Security remains independent of broker wire types.
- Preserve partition offset ordering, immutable ownership, defensive public byte-array APIs, and whole-batch admission.
- Check `BrokerResponse.errorCode()` explicitly. A failed/lost produce response can have an uncertain outcome.
- Extend meaningful behavioral tests when changing contracts. Include recovery and failure paths for storage/admission changes.
- Use the existing Java formatting and package structure. Keep tools dependency-light unless a new dependency has a clear purpose.
- Document API/configuration changes beside the owning module and update cross-cutting guides when behavior changes.

## Repository hygiene

Track source, tests, wrapper scripts, configuration defaults, documentation, and CI-managed benchmark snapshots. Keep local credentials, data, build outputs, IDE state, logs, JFR recordings, heap dumps, and AOT caches out of Git. Runtime data and generated certificates are not disposable source files; stop their owners and inspect them before removing local artifacts.

`benchmark-badges/` contains published JSON/SVG snapshots used by CI. Preserve the README's `benchmark-metrics-badges`, `benchmark-target-diagnostics`, and `benchmark-capacity-details` marker pairs exactly once. Publication tools replace their contents; do not place hand-written documentation inside them.

Performance evidence must identify source revision, JDK, host/resources, storage/filesystem, payload/batch, duration, repetitions, and qualification failures. Distinguish unique consumption from fanout delivery and durable forcing from ingestion-only throughput. Historical results from a different backend do not qualify the current engine.

Before committing, inspect staged and unstaged diffs, run the relevant checks, and use `git diff --check` to catch whitespace errors. Write commit/PR descriptions around the resulting behavior and validation, including any remaining limitation a reviewer needs to assess.
