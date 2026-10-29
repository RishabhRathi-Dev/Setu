# setu-benchmark

This module measures the broker through real TCP traffic and exercises crashes, limits and concurrent progress. Functional requirements belong to [setu-integration-tests](../setu-integration-tests/README.md). Short smoke runs validate mechanics; throughput claims require qualified full measurements.

[FILE I/O measurements](../scripts/diagnostics/io-comparison/README.md#recorded-measurements) consolidate backend
comparisons, integrated-default validation and the Windows flush experiment.
The [comparison harness](../scripts/diagnostics/io-comparison/README.md) supports
`--production` for the integrated default, `--owned` for FileChannel versus
Setu, and `--parity` to include jasyncfio. Experimental dependencies stay outside
production packaging. Qualification criteria and published badges are unchanged.

## Local JFR capture

Use [run-jfr.py](../scripts/run-jfr.py) to build and run the existing TCP workload with JFR on Windows, Linux or macOS. Requires Python 3.10+ and JDK 26 (`JAVA_HOME`, `--java-home` or `java` on `PATH`). The PowerShell entry point forwards the same options:

```powershell
# Capture both IN_MEMORY and FILE sequentially; progress is printed live.
.\scripts\run-jfr.ps1 --java-home 'C:\path\to\jdk-26'

# Longer FILE capture with an explicit new output folder.
python scripts/run-jfr.py --storage-mode FILE --warmup-millis 5000 --stage-millis 10000 --output logs/jfr/file-session
```

On Linux/macOS use `python3 scripts/run-jfr.py`. Run `--help` for heap, CPU count, payload, batch, concurrency and worker timeout options. Paths are resolved from the caller's working directory; the scripts themselves can be invoked from anywhere.

Each new `logs/jfr/<timestamp>-<id>/` contains `build.log`, `run.json` (settings, source revision, exact commands and capture status), and one folder per storage mode with `sustained.jfr`, `worker.log`, `setu.log` and raw `stages.json` markers. Existing output folders are rejected to preserve earlier recordings. Broker scratch data is removed after each worker.

Defaults are 1 KiB records, 256-record batches, at most two producer/consumer pairs, 512 MiB heap, 128 MiB direct memory, 64 MiB retained memory log, two-second warmup and three-second measurement per stage. Each worker ramps concurrency, then runs 50%/80%/100% of its healthy knee when one exists. An unhealthy ramp can end the run early; inspect `worker.log` for which stages ran. JFR uses the worker's existing `profile` configuration and 128 MiB rolling size limit; long captures can lose earlier events and fail the completeness check.

The launcher verifies successful worker completion, traffic without request errors, readable JFR and matching measured-stage markers. Add `--analyze` to save measured-stage `CAPACITY_JFR` and `CAPACITY_FORCE` summaries in `write-path.txt`; without it, analysis remains a separate step. These are diagnostic smoke captures with broker and clients in the same JVM and no OS resource quota; `qualified=false` is expected. Use the resource-enforced profiles below for qualified measurements.

`CAPACITY_JFR` summaries include measured-stage produced/consumed counts and estimated allocation bytes per produced/consumed record, alongside allocation MiB, GC pause time and GC cycle time. Both allocation ratios use the entire broker-and-client JVM estimate; they do not attribute bytes to individual roles. A zero count reports a zero ratio, and older recordings without counts report zero counts. GC cycle time sums each cycle's overlap with the stage, including concurrent work; overlapping cycles can exceed the stage duration. It is elapsed cycle duration rather than GC CPU time; pause time remains separate. Compare these with the matching stage's latency percentiles, excluding warmup and cleanup.

`CAPACITY_FORCE` lines additionally split measured JFR force events into partition data, consumer-journal data, their roll metadata, and other files, separately for full-metadata and data-only barriers. Counts and elapsed-time percentiles use the same stage clipping as the aggregate force metrics. This classification runs after recording and adds no broker instrumentation. It distinguishes journal work from partition work without using individual file paths as report labels; aggregate qualification remains unchanged.

## Run profiles

Use Java 26 and Python 3.10+. The resource-enforced launcher requires Linux/WSL, systemd and cgroup v2. Run commands from the repository root:

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
| `baseline` | Measure the fixed 2-CPU / 5 GiB CI envelope separately from reference targets |
| `steady` | Separate broker/client JVMs at fixed load, with one and four partitions |
| `chaos` | Bounded broker failure/limit checks |
| `sweep`, `large-batch` | Historical fanout and batch-size diagnostics |
| `capacity` | Explicit exhaustion ramp; does not qualify sustained throughput |

For a Windows smoke without OS resource enforcement:

```powershell
.\mvnw.cmd -pl setu-benchmark -am '-Dtest=SustainedThroughputBenchmarkTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dsetu.benchmark.max.concurrency=2' test
```

All child JVMs load the shared [JVM configuration](../config/jvm.options). The launcher defaults to 80% of detected CPU/memory/free-disk availability; `--fraction` and per-resource fractions adjust it. CPU allocation rounds up to whole cores, capped by available capacity and the selected profile: 1.6 becomes 2 cores and 3.2 becomes 4 cores. Fractional ancestor quotas remain hard ceilings. The fixed baseline now uses 2 cores with its existing 5 GiB memory limit; historical 1.6-core captures retain their recorded budget. The selected budget is reused for every repetition. Heap, direct-memory and retained-log allocations reserve process headroom. File space is sampled during measurement against a reserve, rather than enforced as a disk quota.

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

Baseline, target, sustained and steady measurements report unmet performance expectations as warnings by default, while retaining measured values and failures. Baseline/target/sustained results retain their unqualified status; steady reports missed scheduled records separately from delivered records. CI passes completed expectation misses and retains the captures. Crashes, nonzero worker exits, timeouts, request errors, zero producer/consumer progress and incomplete or invalid captures still fail. `--report-only` selects this policy explicitly; `--strict` opts into failing on unmet performance expectations.

Measured JFR stages include `file_force_count`, `file_force_total_ms`, `file_force_p99_ms` and `file_force_max_ms`. New recordings enable every `jdk.FileForce` and `io.setu.storage.FileForce` event with a zero threshold, combining JDK metadata forces and native data forces without double counting. Each call's elapsed wait is clipped to the measured interval, excluding warmup and cleanup; concurrent calls are summed separately, so total wait can exceed the stage duration. These are elapsed durability-barrier times, including any scheduling delay during the call. Older recordings can omit fast events, and older summaries without these metrics display `unavailable`. Reports retain the latency components separately so force waits can be compared with produce and scheduled-completion p99. Wait metrics are diagnostic and have no additional qualification threshold.

## Isolated steady throughput

The broker publishes its readiness port atomically before the load JVM starts, so readiness cannot expose an empty port file. `--kind steady` runs separate broker and client JVMs for both storage modes, with one and four partitions. The workload offers 10,000 records/s for three seconds after warmup. Each completed capture includes accepted/delivered records, measured rates, missed scheduled records, request errors, drain time, and request/scheduled-completion latency. `missed_records` counts records whose scheduled send slots were skipped before the deadline; it is not a count of lost accepted records.

```bash
python3 scripts/run-benchmarks.py --kind steady --payload-sizes 1024 --report-only
python3 scripts/run-benchmarks.py --kind steady --payload-sizes 1024 --strict
```

By default, missed scheduled load emits a warning and all four configurations continue. Strict mode fails after retaining all completed configurations if any missed their offered load. Direct Maven runs use the same default; add `-Dsetu.benchmark.steady.enforce=true` to enforce it. A worker must still exit successfully, emit exactly one matching measured stage, make progress, report zero request errors, and drain every accepted record. Invalid/incomplete counters and delivery mismatches always fail. Raw broker/client output is printed even when a run fails and remains in `logs/benchmarks/steady/`.

These short diagnostics do not qualify sustained capacity. CPU scheduling, JIT/GC, and storage stalls can affect offered-load attainment; inspect the retained timings and resource measurements before attributing a miss to any one cause.

## Direct node-target verification

The mandatory [contributor PC pre-push diagnostic](../scripts/README.md#contributor-pc-check-before-every-push) runs separately from these full target measurements: two fresh JVMs per mode, 5-second minimum adaptive warmups and 10-second fixed-load windows. It uses the same TCP workload, targets, record/batch sizes and durability boundary, but has no OS resource quota and is always unqualified. CI retains the attached PC report alongside its own benchmarks; default-branch publication displays both sources separately.

This profile offers 500K/s in memory with one pair and 250K/s with file storage with two pairs. Three fresh JVMs per mode measure 50%, 80% and 100% of the reference target. All nine windows per mode must qualify.

```bash
python3 scripts/run-benchmarks.py --kind target --measurement full \
  --storage-mode IN_MEMORY --payload-sizes 1024
python3 scripts/run-benchmarks.py --kind target --measurement full \
  --storage-mode FILE --payload-sizes 1024
```

Completed target misses warn by default; add `--strict` to enforce the target gate. Crashes, request errors and incomplete runs remain errors. The Java test owns the three fresh-JVM repetitions. Direct Maven execution is also available:

```powershell
.\mvnw.cmd -pl setu-benchmark -am '-Dtest=NodeTargetThroughputBenchmarkTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dsetu.benchmark.node.targets=true' '-Dsetu.benchmark.cpu.cores=8' '-Dsetu.benchmark.memory.log.bytes=67108864' test
```

Direct Maven runs do not impose an OS CPU quota. Target defaults are 256 MiB memory / 128 MiB file heaps; JFR is enabled by default and saved beside captures under `logs/benchmarks/node-target/`. These long runs are opt-in. Their unmet expectations warn by default; `-Dsetu.benchmark.node.targets.enforce=true` enables strict enforcement for direct Maven runs.

## Default OpenHFT storage

`FILE` measures the production Chronicle Queue 2026.7 adapter directly. Supported Linux uses the Setu-owned io_uring path with full metadata barriers on first use/growth; other platforms use the portable mapped-file/channel path. OpenHFT's no-op native Windows sync is not counted as durable completion. See [storage guarantees and migration](../setu-storage/README.md).

Use the resource-enforced profiles above to measure this adapter on the intended deployment filesystem. Record the source revision, JDK, host/resource budget, filesystem/media, payload and batch sizes, warmup, measurement duration, repetitions and qualification failures with each published result. Local smoke captures and results from other storage engines do not establish Chronicle Queue capacity. The [main README](../README.md#performance) links CI-maintained results and their qualification status.

## CI and reports

[CI](../.github/workflows/ci.yml) runs production/helper tests, integration tests, bounded chaos, isolated load, sustained smoke, two full fixed-budget baseline shards and two full node-target shards with JFR on pushes/PRs. Steady, baseline, target and optional full sustained measurements use `--report-only`: completed misses warn and remain visible in captures/reports, while execution failures fail the job. Optional `full_benchmark` dispatch adds eight capacity comparisons across two modes and four payloads.

CI also runs one redline exhaustion shard per storage mode on every ordinary push/PR/manual run. Each shard uses the `github-private` resource profile, the selected resource fraction (default 80%), 512 MiB worker heap, 128 MiB direct memory, a 64 MiB retained-log budget and disk-backed `/var/tmp`. systemd/cgroup v2 enforces the shared process-tree CPU/memory budget; the free-disk reserve is sampled. The dedicated job summary and `redline-<mode>-<attempt>` artifacts retain `redline.json`, `redline.md`, raw output, Surefire reports and JFR, including execution failures. Default-branch publication updates a separate `benchmark-redline` README block and `benchmark-badges/redline-results.json`, preserving fixed-target qualification and contributor reports. Missing/failed captures are labeled incomplete, and stale revisions cannot overwrite newer reports.

`scripts/diagnostics/StorageForceProbe.java` measures two mapped writers with full and data-only barriers at any supplied disk path, independently of broker work. Compile it with Java 26 and run `io.setu.storage.log.StorageForceProbe <directory>` with `@config/jvm.options -Xms64m -Xmx128m -XX:ActiveProcessorCount=2`; use the same OS resource limits when comparing it with a baseline. The probe preserves offered-load backlog and rejects RAM filesystems. It diagnoses a storage path; it does not qualify or replace the full benchmark.

The optional `trace_file_waits` workflow dispatch runs only the original FILE baseline while sampling worker kernel stacks, per-thread scheduler counters, cgroup CPU/memory/I/O counters and disk statistics. The observer's CPU use is recorded. This is an instrumented investigation run, not evidence that an implementation fix has qualified; the other workflow jobs are intentionally skipped.

Combine it with `trace_storage_only` to trace the short broker-free storage probe instead. Both modes retain their JFR/kernel counters and preserve the normal baseline configuration. Use these captures to investigate storage stalls; qualification still requires the complete resource-enforced benchmark.

Default-branch publication validates source revision, resources, timing, durability and all measured JFR stages before updating JSON, SVG badges and the [main README](../README.md#performance). CI results publish even when the contributor attachment check fails; a successful attachment check also refreshes the separate contributor section. Contributor publication errors do not prevent committing the validated CI report, and still fail the publication job. Missing CI captures are labeled incomplete. Older workflows cannot overwrite newer source measurements. `render-benchmark-badges.py` regenerates images/links from committed snapshots; legacy historical sections are optional and remain separate when present.

Artifacts include `comparison.json`, `comparison.txt`, raw worker captures, JFR and an `optimization.md` analysis, including failed stages. Reanalyze without rerunning load:

```bash
python3 scripts/sustained_report.py --input logs/benchmarks/resources/<run>/comparison.json
```

Smoke artifacts exclude temporary broker data. Publication and report tests run with:

```bash
python -m unittest discover -s scripts -p 'test_*.py'
```

## Bounded chaos and exhaustion

```powershell
.\mvnw.cmd -pl setu-benchmark -am '-Dtest=*ChaosTests,!ProgressiveCapacityChaosTests,QuickBrokerChaosBenchmarkTests' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Bounded tests cover process-kill recovery, malformed frames, record-size limits, expiry, quotas and concurrent consumer progress. `ProgressiveCapacityChaosTests` is a separate failure-seeking diagnostic: it continues doubling load toward runtime limits, counts broadcast fanout and uses a coarse latency histogram. Run it explicitly through `--kind capacity`; its peaks do not qualify sustained throughput or update current badges.

```bash
python3 scripts/run-benchmarks.py --kind capacity --storage-mode FILE --payload-sizes 1024 \
  --profile github-private --fraction 0.8 --heap-mib 512 --direct-mib 128 \
  --memory-log-mib 64 --data-directory /var/tmp
```

The launcher uses one fresh JVM per selected mode, 256-record batches, 2s warmup/windows and no configured concurrency ceiling. From stage eight, sustained CPU saturation adds a 10s observation hold before doubling continues. A measured runtime boundary can occur earlier under a smaller allocation; the launcher requires at least the first completed stage and its JFR evidence, independently of the CPU-hold threshold. Direct Maven runs retain their existing minimum-stage default. A measured heap OOM is an expected terminal result; an OS-killed process without a terminal marker, invalid/missing captures, timeouts and unexpected worker exits remain execution failures. Reports are saved before evaluating failures. The last measured window may precede the terminal attempt; a surviving CPU-hold prefix is labeled partial.

The [contributor pre-push check](../scripts/README.md#contributor-pc-check-before-every-push) runs the same two exhaustion workers after its fixed-target windows, with local JVM limits and no OS CPU/memory quota. Its schema-2 attachment and pipeline summary include both profiles. Redline measurements report peak production, fanout consumption, coarse latency, sampled heap/CPU, attempted concurrency and the stop reason; these short windows do not establish sustained capacity. Heap-exhausted JFR is best effort and can omit the terminal stage.

### Reading the reports

Fixed-target reports show requested writes/s, actual unique writes/s and unique deliveries/s. **Write target achieved** is actual writes divided by requested writes. **Delivery coverage** is delivered records divided by written records within the window. A broker completing 50K writes/s and 50K deliveries/s against a 250K writes/s target has 100% delivery coverage but only 20% write-target attainment. Qualification still includes the existing latency, CPU, heap and error requirements.

Redline reports use broadcast fanout: every consumer reads the same partition. **Deliveries needed/s = unique writes/s × consumers**, and **delivery coverage = completed deliveries / (written records × consumers)**. For 100K writes/s and eight consumers, 400K deliveries/s covers only 50% of the 800K deliveries/s demand. Raw fanout delivery rates can exceed unique write rates even while consumers fall behind. The report shows this coverage next to the traffic rates and places timing/resources in a separate table.

Coverage uses measured counters and describes aggregate progress in that window, rather than record loss or the progress of the slowest individual consumer. In-flight operations at the boundary can produce a ratio slightly above 100%; a zero-write window displays n/a. The window status **Measured** means the load step finished, rather than passed qualification; **Partial CPU hold** identifies a surviving prefix. The summary ties the highest observed write rate to its consumer count and delivery coverage. Neither that peak nor a completed redline stage establishes sustained capacity. Re-rendering saved snapshots changes the presentation and derived ratios only; it retains the original measurements, source revisions and qualification.

## Mixed identified/ordinary writes

`python examples/recovery-demo/run.py --contention` measures two writers across separate partitions in ordinary, identified and mixed modes, recording per-writer p99 and aggregate FILE gate wait. It is a bounded closed-loop diagnostic with explicit unqualified status, not a replacement for resource-enforced offered-load profiles. See [parameters and evidence](../examples/recovery-demo/README.md#contention-diagnostic).
