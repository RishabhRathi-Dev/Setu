# Contributing

Setu uses a Maven reactor with seven modules and JDK 26. Start with the [README](README.md), [architecture](docs/architecture.md), and the README for the module you are changing.

## Before you start

Search [existing issues](https://github.com/RishabhRathi-Dev/Setu/issues) and pull requests for related work. For a substantial API, protocol, storage-format, or dependency change, describe the use case and compatibility implications in an issue before implementation. Small fixes and documentation improvements can go directly to a pull request.

Use [SUPPORT.md](SUPPORT.md) for usage questions and bug-report requirements. Report vulnerabilities privately according to [SECURITY.md](SECURITY.md). Coding agents should also read the repository [knowledge base](AGENTS.md).

## Development setup

1. Install JDK 26 and set `JAVA_HOME` and `PATH`.
2. Use the checked-in Maven Wrapper; it pins Maven in `.mvn/wrapper/maven-wrapper.properties`.
3. Install Python 3.10+ for benchmark tooling and report tests; they use the standard library.
4. Import the root `pom.xml` into your IDE. IDE settings and generated artifacts remain local.

IntelliJ users can use the checked-in [run and debug configurations](docs/intellij.md) under `.run/`. The profiles cover both storage modes and transports, examples, JUnit debugging, and Maven verification; personal `.idea/` settings remain ignored.

Ordinary build and functional checks run on Windows, Linux, and macOS. Resource-enforced benchmark profiles require Linux/WSL with systemd and cgroup v2. All commands below run from the repository root; substitute `bash ./mvnw` for `.\mvnw.cmd` on Linux/macOS.

Confirm the toolchain before building:

```powershell
java -version
.\mvnw.cmd -version
python --version
```

Both Java commands should identify JDK 26. Python is needed when working on tooling. Build and run the [quickstart](README.md#quick-start) to verify your setup, or use the [embedded example](examples/README.md#embedded-broker-and-memory-expiry) for a self-contained broker exercise.

## Verification

| Change | Command / guide |
| --- | --- |
| Production behavior or API | `./mvnw.cmd -pl setu-integration-tests -am test` |
| Benchmark helpers, excluding chaos/load entry points | `./mvnw.cmd -pl setu-benchmark -am '-Dtest=*Tests,!*ChaosTests,!*BenchmarkTests' '-Dsurefire.failIfNoSpecifiedTests=false' test` |
| Python launchers, reports, or publication | `python -m unittest discover -s scripts -p 'test_*.py'` |
| Standalone packaging | `./mvnw.cmd -pl setu-server -am package` and the [quickstart](README.md#quick-start) |
| Docker image or deployment | `docker build -t setu:local .`, then `python scripts/verify-container.py --image setu:local`; see [container verification](docs/containers.md#verification) |
| Documentation | `python scripts/check-docs.py` and `git diff --check` |
| Security integration | [TCP/mTLS integration tests](setu-integration-tests/README.md) |
| Performance or durability | [Benchmark profiles and evidence requirements](setu-benchmark/README.md) |

Use `-am` when building a module with reactor dependencies. The full root reactor includes stress and benchmark workloads; choose the explicit suites above for ordinary development. Maven loads the shared `config/jvm.options`. Surefire reports are written under each module's `target/surefire-reports/`.

CI runs the functional reactor on Windows alongside Linux, including real TCP/mTLS and file retention; the Windows job also exercises the packaged PowerShell launcher. All action references are pinned to commit SHAs, with Dependabot updates for actions, Maven dependencies and the Docker base image. The [Container image workflow](.github/workflows/container.yml) runs on pushes, pull requests and manual dispatch, verifies the image and durable restart, and retains a downloadable image archive. Successful default-branch runs publish that tested image to GHCR; pull requests do not publish. Manual runs are available through **Actions → Container image → Run workflow**. See [publication requirements](docs/containers.md#published-image).

[Security CI](.github/workflows/security.yml) runs on pushes, pull requests, weekly, and manually. CodeQL builds production sources on JDK 26 with the [security query suite](.github/codeql/security.qls), which includes every `security-extended` query plus `AlertSuppression.ql` to emit source-annotation metadata. [check-codeql.py](scripts/check-codeql.py) fails on security severity 7 or above and error-level results; lower-severity findings remain in the retained SARIF. Missing or invalid reports fail the gate. A query-specific source annotation may document a reviewed false positive; its accepted SARIF `inSource` suppression is honored, while external or unaccepted suppressions still block. The job retains evidence for 14 days and requires network access to download CodeQL. It uses GitHub-provided authentication and needs no user-supplied API key. The CodeQL job grants the built-in token `contents: read` and `actions: read` for checkout and workflow-run metadata. It uses local SARIF gating with `upload: never` and `upload-database: false`, without requiring GitHub code-scanning uploads or write permissions. Repository administrators must select this job as a required check in branch protection if merges must enforce them.

CI also exercises bounded chaos, isolated load, baseline/target measurements, and secure AOT training. Deliberate missing-client-certificate tests may log `certificate_required`. Benchmark expectation misses warn by default while remaining unqualified in reports; `--strict` enforces performance expectations. Crashes, invalid captures, request errors, and zero progress remain execution failures.

For documentation-only changes, check relative links and heading anchors, verify commands/defaults against their source, and run `git diff --check`. If you change the README's generated benchmark layout or publication scripts, run the Python suite as well. Do not run load profiles solely to validate prose changes.

## Pull request workflow

Install the mandatory [contributor PC pre-push check](scripts/README.md#contributor-pc-check-before-every-push) with `python scripts/contributor-performance.py install`. Every branch-tip push must carry its local two-repeat diagnostic alongside the existing CI benchmarks. The check takes at least three minutes plus build/analysis, requires a clean checkout and JDK 26, and preserves performance misses as unqualified measurements. CI rejects missing or mismatched attachments; branch protection must require `contributor-performance` to enforce this on merges.

The check also runs one progressive redline ramp per storage mode after the fixed-target windows. These ramps add runtime/load, use local JVM limits, and report the terminal resource/admission limit as an unqualified diagnostic. CI repeats them under enforced CPU/memory limits and publishes a separate exhaustion report. Updated schema-2 attachments require both profiles; older attachments must be remeasured. See the [redline guide](setu-benchmark/README.md#bounded-chaos-and-exhaustion) for timing, resource limits and failure handling.

CI can reuse a measured immediate parent's attachment after merging newer generated benchmark reports. Only the generated root README blocks and non-executable JSON/SVG badge snapshots may differ; code, configuration, tools and manual documentation must match. Reports retain the measured source identity and record the merge revision separately. See [attachment collection](scripts/README.md#contributor-pc-check-before-every-push) for the exact comparison boundary.

1. Create a focused branch from the current default branch; `codex/<short-description>` is the convention for agent-created branches.
2. Keep the change within the owning module's boundary. Add meaningful behavioral coverage when a contract changes.
3. Update the module README and any affected cross-cutting guides, configuration examples, or runnable examples.
4. Run the applicable checks above and inspect the complete diff for generated files, credentials, and unrelated edits.
5. Open a pull request using the provided template. Explain the problem, resulting behavior, verification results, and compatibility or operational effects. Link a related issue when one exists.

State verification limits explicitly, including unavailable tools or unsupported benchmark environments. Performance claims need reproducible evidence and qualification results. Keep follow-up fixes in the same pull request focused on the proposed change.

## Implementation conventions

- Keep transport/policy in server, broker semantics and protocol in core, and log/durability behavior in storage. Security remains independent of broker wire types.
- Preserve partition offset ordering, immutable ownership, defensive public byte-array APIs, and whole-batch admission.
- Check `BrokerResponse.errorCode()` explicitly. A failed/lost produce response can have an uncertain outcome.
- Extend meaningful behavioral tests when changing contracts. Include recovery and failure paths for storage/admission changes.
- Use the existing Java formatting and package structure; [.editorconfig](.editorconfig) defines whitespace defaults. Keep tools dependency-light unless a new dependency has a clear purpose.
- Give each production class, interface, record and enum its own named file in the owning subsystem package. Keep implementation helpers package-private; test-local fixtures may remain nested. See [Java source layout](docs/source-layout.md).
- Document API/configuration changes beside the owning module and update cross-cutting guides when behavior changes.

## Repository hygiene

Track source, tests, wrapper scripts, configuration defaults, documentation, and CI-managed benchmark snapshots. Keep local credentials, data, build outputs, IDE state, logs, JFR recordings, heap dumps, and AOT caches out of Git. Runtime data and generated certificates are not disposable source files; stop their owners and inspect them before removing local artifacts.

`benchmark-badges/` contains published JSON/SVG snapshots used by CI. Preserve the README's `benchmark-metrics-badges`, `benchmark-target-diagnostics`, and `benchmark-capacity-details` marker pairs exactly once. Publication tools replace their contents; do not place hand-written documentation inside them.

Performance evidence must identify source revision, JDK, host/resources, storage/filesystem, payload/batch, duration, repetitions, and qualification failures. Distinguish unique consumption from fanout delivery and durable forcing from ingestion-only throughput. Historical results from a different backend do not qualify the current engine.

Before committing, inspect staged and unstaged diffs, run the relevant checks, and use `git diff --check` to catch whitespace errors. Write commit/PR descriptions around the resulting behavior and validation, including any remaining limitation a reviewer needs to assess.

## Source quality and distributions

Run `python scripts/check-source.py` for source formatting. Java compilation enables selected `empty`, `fallthrough`, `finally` and `overrides` diagnostics with warnings treated as errors. Keep API/format changes covered by behavior tests, not just style gates.

For journal or packaging changes, run `python examples/recovery-demo/run.py`, `python scripts/package-distribution.py` and `python scripts/verify-distribution.py`. The demo retains process-kill and retry evidence under `examples/target/`. `--contention` selects the unqualified mixed-write diagnostic. Preserve the existing qualified benchmark criteria.
