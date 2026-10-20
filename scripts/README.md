# Development tools

Run tools from the repository root unless a guide specifies otherwise. Java tools require JDK 26. Python tools use Python 3.10+ and the standard library. Generated evidence belongs under ignored `logs/`; certificates belong under `certs/generated/`.

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

## Badge publication

| Tool | Purpose |
| --- | --- |
| [update-benchmark-badges.py](update-benchmark-badges.py) | Shared badge helpers; legacy capacity publication requires a README template with a `benchmark-historical-badges` block |
| [publish-sustained-badges.py](publish-sustained-badges.py) | Publish validated sustained measurements into JSON/SVG snapshots and README blocks |
| [publish-node-target-badges.py](publish-node-target-badges.py) | Publish node-target results, latency/resource/JFR diagnostics, and qualification details |
| [render-benchmark-badges.py](render-benchmark-badges.py) | Re-render badge SVGs and README links from committed snapshots |

These tools update tracked assets under `benchmark-badges/`. CI stages those assets together with the root README. Keep the generated marker pairs intact; use the supplied tests before changing publication behavior.

## Storage diagnostics

| Tool | Purpose |
| --- | --- |
| [diagnostics/FileForceProbe.java](diagnostics/FileForceProbe.java) | Check mapped-file barriers, mapped growth/header rewrites, and delayed-force backlog accounting |
| [diagnostics/StorageForceProbe.java](diagnostics/StorageForceProbe.java) | Compare full and data-only barriers with two mapped writers on a selected disk path |
| [diagnostics/trace-force-waits.py](diagnostics/trace-force-waits.py) | Linux kernel/resource observer used by the optional CI tracing job |

Probes diagnose storage behavior and do not qualify broker throughput. Compilation/runtime commands are documented in [storage tests and diagnostics](../setu-storage/README.md#limits-and-tests) and [benchmark CI and reports](../setu-benchmark/README.md#ci-and-reports).

## Tests

```bash
python -m unittest discover -s scripts -p 'test_*.py'
```

The suites cover resource planning/enforcement, parser validation, sustained/target qualification, badge publication, and JFR capture validation/lifecycle. Test artifacts remain under ignored temporary/build directories.

## Source security gate

`python scripts/check-codeql.py <sarif-directory>` validates CodeQL evidence and fails on security severity >=7 or error-level results. Missing/invalid evidence fails. [Security CI](../.github/workflows/security.yml) retains full SARIF, including lower-severity findings. Run `python -m unittest discover -s scripts -p 'test_*.py'` after changing this gate.
