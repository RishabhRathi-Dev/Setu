# setu-benchmark

This module measures the broker through real TCP traffic and exercises crashes, limits and concurrent progress. Functional requirements belong to [setu-integration-tests](../setu-integration-tests/README.md). Short smoke runs validate mechanics; throughput claims require qualified full measurements.

## Run profiles

Use Java 26 and Python 3. The resource-enforced launcher requires Linux/WSL, systemd and cgroup v2. Run commands from the repository root:

```bash
# Inspect detected hardware and the planned resource allocation.
python3 scripts/run-benchmarks.py --dry-run

# Short sustained smoke for 1 KiB records.
python3 scripts/run-benchmarks.py --kind sustained --measurement smoke --payload-sizes 1024

# Full sustained comparison: three repetitions, 10s+ warmup, 30s measurement.
python3 scripts/run-benchmarks.py --kind sustained --measurement full

# One full capacity shard.
python3 scripts/run-benchmarks.py --kind sustained --measurement full \
  --storage-mode FILE --payload-sizes 1024
```

| Kind | Purpose |
| --- | --- |
| `sustained` | Find a healthy throughput knee, then measure fixed loads at 50%, 80% and 100% of it |
| `target` | Check fixed reference targets directly, independently of the measured knee |
| `steady` | Separate broker/client JVMs at fixed load, with one and four partitions |
| `chaos` | Bounded broker failure/limit checks |
| `sweep`, `large-batch` | Historical fanout and batch-size diagnostics |
| `capacity` | Explicit exhaustion ramp; does not qualify sustained throughput |

For a Windows smoke without OS resource enforcement:

```powershell
.\mvnw.cmd -pl setu-benchmark -am '-Dtest=SustainedThroughputBenchmarkTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dsetu.benchmark.max.concurrency=2' test
```

All child JVMs load the shared [JVM configuration](../config/jvm.options). The launcher defaults to 80% of detected CPU/memory/free-disk availability; `--fraction` and per-resource fractions adjust it. The selected budget is reused for every repetition. Heap, direct-memory and retained-log allocations reserve process headroom. File space is sampled during measurement against a reserve, rather than enforced as a disk quota.

Useful options include `--max-concurrency`, `--heap-mib`, `--direct-mib`, `--memory-log-mib`, `--data-directory` and `--output`. Default payloads are 256/512/1024/4096 bytes; Java workers default to 256-record batches. `--stage-millis`, `--warmup-millis` and `--repeats` remain subject to profile validation. See [run-benchmarks.py](../scripts/run-benchmarks.py) for all options.

## Measurement and qualification

Broker, server and load clients share the worker's allocated resources in the sustained/target profiles. Each producer/consumer pair owns a single-partition topic; consumption counts unique deliveries. Setup, warmup and shutdown do not add to measured throughput.

The sustained ramp doubles pairs up to a power-of-two ceiling no greater than twice the CPU budget, unless capped lower. The first unhealthy stage stops the ramp. The highest healthy producer rate is the knee within that range; a healthy ceiling is only a lower bound on capacity. Fixed-load stages use that knee's concurrency.

Full runs require three independent repetitions and at least 10-second warmup / 30-second measurement per stage. Warmup compares recent one-second rates, allowing at most 5% drift and extending to 60 seconds. Smoke uses two-second warmup / three-second measurement and is always unqualified.

| Criterion | IN_MEMORY | FILE |
| --- | ---: | ---: |
| Reference target, 1 KiB | 500,000 records/s | 250,000 records/s |
| Full capacity gate, 1 KiB | 200,000 records/s | 150,000 records/s |
| p99 at 50% load | <5 ms | <10 ms |
| p99 at 80% / 100% load or ramp | <50 ms | <100 ms |
| Consumption and offered-throughput attainment | At least 95% | At least 95% |
| Request errors/timeouts | Zero | Zero |
| Average CPU / peak heap | <=85% / <=1 GiB | <=85% / <=1 GiB |
| Used-heap slope | <=1 MiB/s | <=1 MiB/s |

Heap slope uses actual jittered sample times, roughly 40–70 ms apart. Full capacity qualification requires all three knees and their fixed-load stages to pass; its sustained rate is the median knee. The 200K/150K gates apply to 1 KiB full capacity runs, rather than every payload size.

HDR histograms retain produce, fetch, acknowledgement and scheduled-completion latency separately. The headline percentile is their maximum, preventing many fast operations from diluting a slow produce path. Scheduled latency includes overdue slots and memory-admission waits; missed records and actual completed throughput remain visible. These are request latencies, not per-record end-to-end delivery latencies. Backlog, consumption, CPU, heap and records/s per allocated core accompany every rate.

`FILE` forces every group commit before acknowledgement; memory mode is volatile. Filesystem/media details are recorded rather than assuming SSD durability/performance. Reports reject incomplete captures, duplicate runs, non-finite metrics, mixed payloads and invalid offered rates. Evidence is saved before evaluating qualification failures.

## Direct node-target verification

This profile offers 500K/s in memory with one pair and 250K/s with file storage with two pairs. Three fresh JVMs per mode measure 50%, 80% and 100% of the reference target. All nine windows per mode must qualify.

```bash
python3 scripts/run-benchmarks.py --kind target --measurement full \
  --storage-mode IN_MEMORY --payload-sizes 1024
python3 scripts/run-benchmarks.py --kind target --measurement full \
  --storage-mode FILE --payload-sizes 1024
```

Add `--report-only` to retain completed target misses without failing the target gate; crashes, request errors and incomplete runs remain errors. The Java test owns the three fresh-JVM repetitions. Direct Maven execution is also available:

```powershell
.\mvnw.cmd -pl setu-benchmark -am '-Dtest=NodeTargetThroughputBenchmarkTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dsetu.benchmark.node.targets=true' '-Dsetu.benchmark.cpu.cores=8' '-Dsetu.benchmark.memory.log.bytes=67108864' test
```

Direct Maven runs do not impose an OS CPU quota. Target defaults are 256 MiB memory / 128 MiB file heaps; JFR is enabled by default and saved beside captures under `logs/benchmarks/node-target/`. These long runs are opt-in.

## Default OpenHFT storage

`FILE` measures the production Chronicle Queue 2026.6 adapter directly. It uses JDK mapped-file/channel forces, rather than counting OpenHFT's no-op native Windows sync as durable completion. See [storage guarantees and migration](../setu-storage/README.md).

The 15 October local Windows/NTFS diagnostic used Java 26.0.2.1, eight active processors without an OS CPU quota, 128 MiB heap, 1 KiB records, 256-record batches, JFR, five-second warmup and ten-second stages. All five stages completed without request errors, but results were **unqualified**; half-load p99 exceeded the 10 ms gate.

| Chronicle FILE stage | Pairs | Actual produce/s | p99 ms | Consume ratio |
| --- | ---: | ---: | ---: | ---: |
| Unpaced ramp | 1 | 35,404.8 | 12.812 | 0.9928 |
| Unpaced ramp | 2 | 54,604.8 | 15.884 | 0.9981 |
| 100% of measured knee | 2 | 54,579.2 | 47.055 scheduled | 1.0005 |

This was substantially slower than the former custom backend on that host; it does not establish attainment of the 250K/s target. The local capture is `logs/benchmarks/sustained/FILE-84f90463-3003-4c54-ab33-beaa184afa6f/worker.log`, with JFR alongside it. Reproduce through `SustainedThroughputBenchmarkTests#file` using `setu.benchmark.cpu.cores=8`, `setu.benchmark.heap.mib=128`, `setu.benchmark.max.concurrency=2`, `setu.benchmark.stage.millis=10000`, `setu.benchmark.warmup.millis=5000` and `setu.benchmark.jfr=true`.

## CI and reports

[CI](../.github/workflows/ci.yml) runs production/helper tests, integration tests, bounded chaos, isolated load, sustained smoke and two full node-target shards with JFR on pushes/PRs. Automatic target shards use `--report-only`, since runner allocations can be below the reference host budget. Completed misses are unqualified results; execution failures remain errors. Optional `full_benchmark` dispatch adds eight capacity comparisons across two modes and four payloads.

`scripts/diagnostics/StorageForceProbe.java` measures two mapped writers with full and data-only barriers at any supplied disk path, independently of broker work. Compile it with Java 26 and run `io.setu.storage.log.StorageForceProbe <directory>` with `@config/jvm.options -Xms64m -Xmx128m -XX:ActiveProcessorCount=2`; use the same OS resource limits when comparing it with a baseline. The probe preserves offered-load backlog and rejects RAM filesystems. It diagnoses a storage path; it does not qualify or replace the full benchmark.

An experimental private disk-backed journal improved a [short probe](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39010874823), but **failed the [complete FILE baseline](https://github.com/RishabhRathi-Dev/Setu/actions/runs/39011113017)**. That environment change was removed. The strict baseline retains the original runner, temp directory, resource limits and gates. See [the investigation](../TODO.md) for retained failures, comparison/JFR evidence and outstanding acceptance checks.

The optional `trace_file_waits` workflow dispatch runs only the original FILE baseline while sampling worker kernel stacks, per-thread scheduler counters, cgroup CPU/memory/I/O counters and disk statistics. The observer's CPU use is recorded. This is an instrumented investigation run, not evidence that an implementation fix has qualified; the other workflow jobs are intentionally skipped.

Default-branch publication validates source revision, resources, timing, durability and all measured JFR stages before updating JSON, SVG badges and the [main README](../README.md#performance). Missing captures are labeled incomplete. Older workflows cannot overwrite newer source measurements. `render-benchmark-badges.py` regenerates images/links from committed snapshots; historical, target and capacity blocks remain distinct.

Artifacts include `comparison.json`, `comparison.txt`, raw worker captures, JFR and an `optimization.md` analysis, including failed stages. Reanalyze without rerunning load:

```bash
python3 scripts/sustained_report.py --input logs/benchmarks/resources/<run>/comparison.json
```

Smoke artifacts exclude temporary broker data. Publication and report tests run with:

```bash
python -m unittest discover -s scripts -p 'test_benchmark*.py'
```

## Bounded chaos and exhaustion

```powershell
.\mvnw.cmd -pl setu-benchmark -am '-Dtest=*ChaosTests,!ProgressiveCapacityChaosTests,QuickBrokerChaosBenchmarkTests' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Bounded tests cover process-kill recovery, malformed frames, record-size limits, expiry, quotas and concurrent consumer progress. `ProgressiveCapacityChaosTests` is a separate failure-seeking diagnostic: it continues doubling load toward runtime limits, counts broadcast fanout and uses a coarse latency histogram. Run it explicitly through `--kind capacity`; its peaks do not qualify sustained throughput or update current badges.

## Earlier measurements

These results predate the Chronicle default and do not qualify its performance. Raw snapshots preserve failed/interrupted attempts as well as final measurements.

| Historical run | Result and scope | Evidence |
| --- | --- | --- |
| 15 October custom backend | All 18 full target windows passed; target medians 499,993.6/s memory and 249,992.5/s file; worst p99 2.023 / 2.826 ms | [Raw snapshot](performance-analysis-2026-10-15.json) |
| 14 October custom backend | All 18 full target windows passed; target medians 499,985/s memory and 249,993/s file; worst p99 2.041 / 8.057 ms | [Raw snapshot](optimization-2026-10-14.json) |
| 12 October CAS/MPSC comparison | Three short fanout comparisons found no material throughput gain; peak changed medians 1,019,401/s memory and 639,125/s file | Local `logs/lock-free/` captures |

The October target host was Ryzen 5 7600X / Windows NTFS / Java 26.0.2.1 with eight active processors, no OS CPU quota, 1 KiB / 256-record batches, 64 MiB memory-log budget and 256 MiB memory / 128 MiB file heaps. Short before/after JFR diagnostics remain in the JSON; they are not substitutes for sustained qualification or resource-enforced CI results.
