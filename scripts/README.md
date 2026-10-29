# Development tools

Run tools from the repository root unless a guide specifies otherwise. Java tools require JDK 26. Python tools use Python 3.10+ and the standard library. Generated evidence belongs under ignored `logs/`; certificates belong under `certs/generated/`.

The Maven entry points retry wrapper-JAR bootstrap downloads on HTTP 429 and transient server errors, with at most five attempts. Downloads use temporary sibling files and publish only after a successful nonempty response; exhausted retries stop before Java launches. Windows uses `.mvn/wrapper/download-wrapper.ps1`; Bash uses curl or wget. Existing nonempty wrapper caches avoid that request, and empty cached files are downloaded again. `test_maven_wrapper.py` exercises rate limits, permanent failures, retry exhaustion and cache handling through a local HTTP server. Windows tests need `JAVA_HOME`; Linux tests exercise each available curl/wget path. This does not add retries to Maven's separate dependency-resolution or distribution-download stages.

Wrapper metadata declares the `script` distribution type because the launchers download and run `maven-wrapper.jar`. When upgrading Maven, update `distributionUrl` in `.mvn/wrapper/maven-wrapper.properties` and preserve the customized `mvnw` and `mvnw.cmd` bootstrap logic. Regenerating stock launchers can remove the retry handling; run the wrapper tests on Windows and Linux after wrapper changes.

## Launch and credentials

| Tool | Purpose |
| --- | --- |
| [start-broker.ps1](start-broker.ps1) / [start-broker.sh](start-broker.sh) | Launch the packaged broker with shared JVM flags; support `--train-aot` and `--no-aot` |
| [generate-certs.ps1](generate-certs.ps1) / [generate-certs.sh](generate-certs.sh) | Generate local development certificate/key stores for the [secure embedding example](../setu-server/README.md#secure-broker-wiring-example) |

Build the server with `-pl setu-server -am package` before launching. Set `SETU_CERT_PASSWORD` before generating development credentials. See [operations](../docs/operations.md) for packaging and startup.

## Workloads and JFR

| Tool | Purpose / platform |
| --- | --- |
| [run-jfr.py](run-jfr.py) | Portable TCP workload/JFR smoke capture; verifies worker completion and measured-stage markers |
| [run-jfr.ps1](run-jfr.ps1) | PowerShell forwarding entry point for `run-jfr.py` |
| [run-benchmarks.py](run-benchmarks.py) | Linux/WSL resource-enforced profiles, workload orchestration, reports, and qualification |
| [node_target_report.py](node_target_report.py) | Parse and validate baseline/target captures and summarize qualification |
| [sustained_report.py](sustained_report.py) | Parse and validate sustained runs, stage metrics, and performance gates |

```powershell
python scripts/run-jfr.py --storage-mode FILE
python scripts/run-jfr.py --help
```

For resource-enforced Linux/WSL runs, start with `python3 scripts/run-benchmarks.py --dry-run`. Baseline, target, sustained, and steady expectation misses warn by default; use `--strict` to fail unmet performance expectations. Execution failures remain errors. [The benchmark guide](../setu-benchmark/README.md) defines measurement profiles, CPU/memory enforcement, output schemas, and qualification rules.

## Contributor PC check before every push

Install the checked-in Git hook once per checkout, with Python 3.10+ and JDK 26 available:

```powershell
python scripts/contributor-performance.py install
```

This sets the local `core.hooksPath` to `.githooks` and remembers the installing Python interpreter. Existing custom hooks are detected and left intact; integrate the `pre-push` command with those hooks instead. Set `JAVA_HOME` to JDK 26, or pass `install --java-home PATH` to remember a JDK for this checkout. The same Python entry point and shell hook work on Windows Git, Linux and macOS. A normal `git push` runs the diagnostic automatically for the checked-out branch tip. Commit or stash tracked and untracked source changes first; do not edit sources during measurement. Branch deletion and tag-only pushes have no new branch tip to measure.

Each push builds with the Maven Wrapper, then runs both storage modes at 50%, 80% and 100% of their reference targets. Each mode runs in two fresh JVMs, with 5-second minimum adaptive warmup and 10-second measurement per load: at least three minutes of workload plus build, JFR analysis and possible warmup extensions. Records are 1 KiB and batches contain 256 records. JVMs use up to eight active processors, a 512 MiB heap, 128 MiB direct-memory limit and a 64 MiB memory-log budget. No OS CPU quota is imposed. FILE retains the production force-before-ack boundary.

The hook then runs one fresh redline JVM per storage mode with the same heap/direct/processor settings. It uses the existing progressive exhaustion worker: 2s warmup, 2s windows, doubled producer/consumer pairs, and a 10s CPU hold from stage eight onward when saturated. It continues past CPU saturation to a measured runtime limit, with a 25-minute deadline per mode and a sampled 20% free-disk reserve. Expect additional runtime and load beyond the fixed-target check. Completed stages, peak production, fanout consumption, sampled CPU/heap, coarse p99 and the terminal reason appear in both the terminal and `summary.md`/`summary.json`.

The check fails on build/worker/JFR errors, zero progress, request errors, source changes and failed attachment uploads. Slow throughput and health misses remain visible; they do not block the push. This shorter profile is always **diagnostic and unqualified**. Full CI benchmarks run separately with their existing qualification criteria; CPU budgets round up within available capacity as described in the [benchmark guide](../setu-benchmark/README.md#run-profiles).

For the redline ramp, a measured JVM OOM (exit 42), resource/admission limit or client stall is an expected diagnostic result after at least one measured window with production and consumption. Unexpected exits, missing terminal markers, configured concurrency ceilings and unreadable JFR checkpoints fail the check. Terminal request failures are reported as the redline boundary; fixed-target request errors still fail. Redline recordings after heap exhaustion contain the last best-effort atomic checkpoint and can omit the failing stage. Compact attachments now use schema version 2 and require both redline modes; remeasure older attachments with the updated hook.

Raw logs, exact commands in `run.json`, recordings and the compact `summary.json` remain under ignored `logs/contributor-performance/`. The attachment contains the contributor's Git name, source commit/tree, timestamps, OS, CPU identification, JDK, filesystem types, JVM limits and all measured windows, including health and force waits. Physical media is labeled unknown rather than inferred. No home-directory paths, raw logs, JFR or credentials are uploaded. To inspect a local run without attaching it, use `python scripts/contributor-performance.py run` (optionally `--java-home PATH`).

The hook stores the summary as a Git note at `refs/notes/setu-performance/<source-commit>` and pushes that attachment ref to the same destination before the original branch push. It never commits or amends source files. A failed branch push can leave an unused attachment; the next push still measures again. Existing notes are fetched before replacement and uploaded with a lease, so concurrent updates cannot silently overwrite each other. The destination must allow writing Git notes.

The `contributor-performance` CI job requires a valid attachment for each pushed/PR branch tip and publishes its summary as a pipeline artifact and job summary. The default-branch badge job publishes validated CI results independently of the attachment check's outcome. When the check succeeds, it also publishes `benchmark-badges/contributor-pc.json` and a separate README block, including a direct link to the workflow report and downloadable results. Missing contributor evidence leaves the previous contributor report in place and still fails the required attachment check. A contributor publication error still fails the badge job, while allowing the validated CI results to be committed. A GitHub merge can reuse its branch parent's evidence only when their complete Git trees match. A squash, rebase, GitHub edit or conflict resolution producing a different tree needs a fresh locally measured push. Fork PR attachments are read from the fork. Contributor evidence is self-reported, not independently verified PC execution.

Git hooks can be bypassed locally; repository administrators must require the `contributor-performance` status check in branch protection to enforce missing-evidence failures on merges. A bypassed push with an old attachment for exactly the same source cannot be distinguished from a repeated measured push by CI. The installed hook itself always measures and never uses a cached result. Automated badge commits retain `[skip ci]` and do not claim to be new measured source revisions.

## Badge publication

| Tool | Purpose |
| --- | --- |
| [update-benchmark-badges.py](update-benchmark-badges.py) | Shared badge helpers; legacy capacity publication requires a README template with a `benchmark-historical-badges` block |
| [publish-sustained-badges.py](publish-sustained-badges.py) | Publish validated sustained measurements into JSON/SVG snapshots and README blocks |
| [publish-node-target-badges.py](publish-node-target-badges.py) | Publish node-target results, latency/resource/JFR diagnostics, and qualification details |
| [redline_report.py](redline_report.py) | Validate exhaustion captures shared by the pre-push hook and CI; publish CI JSON and its separate README report |
| [render-benchmark-badges.py](render-benchmark-badges.py) | Re-render badge SVGs and README links from committed snapshots |

These tools update tracked assets under `benchmark-badges/`. CI stages those assets together with the root README. Keep the generated marker pairs intact; use the supplied tests before changing publication behavior.

## Storage diagnostics

| Tool | Purpose |
| --- | --- |
| [diagnostics/FileForceProbe.java](diagnostics/FileForceProbe.java) | Check mapped-file barriers, mapped growth/header rewrites, and delayed-force backlog accounting |
| [diagnostics/StorageForceProbe.java](diagnostics/StorageForceProbe.java) | Compare full and data-only barriers with two mapped writers on a selected disk path |
| [diagnostics/WindowsIoRingProbe.java](diagnostics/WindowsIoRingProbe.java) | Query Windows I/O-ring support; optionally compare mapped-file JDK and native-ring flushes |
| [diagnostics/trace-force-waits.py](diagnostics/trace-force-waits.py) | Linux kernel/resource observer used by the optional CI tracing job |
| [diagnostics/io-comparison/](diagnostics/io-comparison/README.md) | Isolated Linux overlays comparing Java FileChannel, C++/FFM io_uring and jasyncfio through the actual TCP workload |

Probes diagnose storage behavior and do not qualify broker throughput. Compilation/runtime commands are documented in [storage tests and diagnostics](../setu-storage/README.md#limits-and-tests) and [benchmark CI and reports](../setu-benchmark/README.md#ci-and-reports).

On Windows, run `java --enable-native-access=ALL-UNNAMED scripts/diagnostics/WindowsIoRingProbe.java`
with JDK 26 to query native capabilities. Add a fresh output-directory path to
run four alternating flush comparisons; it retains eight 64 MiB test files.
The experiment requires Windows I/O-ring API version 300 or newer. It preserves
mapped writes and full durability barriers, reports flushes/s, and does not
measure broker throughput. See [FILE I/O measurements](diagnostics/io-comparison/README.md#windows-flush-experiment).

## Tests

```bash
python -m unittest discover -s scripts -p 'test_*.py'
```

The suites cover resource planning/enforcement, parser validation, sustained/target qualification, badge publication, and JFR capture validation/lifecycle. Test artifacts remain under ignored temporary/build directories.

## Source security gate

`python scripts/check-codeql.py <sarif-directory>` validates CodeQL evidence and fails on security severity >=7 or error-level results. Rule metadata can live in the SARIF tool driver or query-pack extensions; the gate resolves component/rule references before checking severity. Missing, ambiguous or inconsistent metadata fails. The [query suite](../.github/codeql/security.qls) explicitly runs `AlertSuppression.ql`. Accepted `inSource` suppressions from narrowly reviewed `// codeql[query-id]` annotations are honored; external, rejected and under-review suppressions still block. Findings include their source file and line when present. [Security CI](../.github/workflows/security.yml) retains full SARIF, including lower-severity findings. Run `python -m unittest discover -s scripts -p 'test_*.py'` after changing this gate.

## Distribution and source quality

`python scripts/check-source.py` checks tracked/new source formatting without dependencies. The compiler treats selected empty-statement, fallthrough, finally and override warnings as errors. `python scripts/package-distribution.py` packages a built server into a versioned ZIP and SHA-256 manifests. `python scripts/verify-distribution.py` checks hashes and exercises the launcher from an extracted temporary directory. Distribution CI runs these checks and the [recovery demo](../examples/recovery-demo/README.md) on Linux and Windows.

`python scripts/check-docs.py` validates repository Markdown file links, heading anchors and the three generated root README marker pairs. It ignores example links inside fenced code blocks and leaves remote URL availability to the owning service.

`python scripts/verify-container.py --image setu:local` exercises a built Docker image with the packaged Java client, verifies health and durable records after restart, and retains logs/inspection results under `target/container-smoke/`. Docker Engine must run Linux containers. The tool creates and removes only uniquely named test containers and volumes; existing application data is untouched. [Container CI](../.github/workflows/container.yml) runs it before retaining or publishing the image. See [container setup](../docs/containers.md) for defaults and registry use.
