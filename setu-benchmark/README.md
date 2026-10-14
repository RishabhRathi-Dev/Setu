# Setu benchmarks and chaos tests

## Sustained throughput profiles

`SustainedThroughputBenchmarkTests` launches a fresh Java 26 worker for each storage mode and repetition. The broker, server and TCP load clients share that worker and its CPU/memory budget. Each producer/consumer pair owns one single-partition topic. A consumer fetches and acknowledges only that topic, so consumption counts unique deliveries rather than broadcast fanout. Setup, warmup and shutdown are excluded from throughput.

Use Java 26 and Python 3 on Linux or WSL with systemd and cgroup v2:

```bash
# Inspect hardware and the enforced resource budget.
python3 scripts/run-benchmarks.py --dry-run

# Routine smoke: 2s warmup and 3s measurement at every concurrency, one repeat.
python3 scripts/run-benchmarks.py --kind sustained --measurement smoke --payload-sizes 1024

# Full comparison: 10s warmup, 30s measurement, three independent repeats.
python3 scripts/run-benchmarks.py --kind sustained --measurement full

# One CI shard (the same host allocation is used for every repeat).
python3 scripts/run-benchmarks.py --kind sustained --measurement full \
  --storage-mode FILE --payload-sizes 1024
```

Full runs rotate payload order between repetitions. Each measured concurrency gets its own warmup. After ten seconds, compare the mean producer rate in the first and second halves of the most recent ten one-second samples; both must be positive and differ by at most 5%. Extend warmup up to 60 seconds if needed. A stage whose warmup never stabilizes cannot qualify. The full profile requires exactly three repetitions, at least ten seconds warmup and at least thirty seconds measurement. Smoke retains short timings and is always labeled unqualified.

The ramp doubles producers and consumers from one, up to the largest power of two no greater than twice the allocated CPU budget (minimum one pair). `--max-concurrency` can lower this ceiling. The first unhealthy stage stops the ramp; the highest healthy producer rate is the knee within the tested range. CPU and heap criteria apply as well as consumption and latency. A healthy ceiling is a lower bound on capacity, not evidence of a runtime limit. The worker then uses the knee's concurrency for three fixed offered-load runs, at 50%, 80% and 100% of that rate, with warmup before each.

Fixed-load producers follow scheduled batch deadlines without dropping overdue slots. Completion latency includes scheduling and memory-admission waits, avoiding omission of delay under overload. `missed_records` includes slots that could not complete in the measurement window. Actual throughput must reach at least 95% of the offered rate. The final batch receives a full service interval; incomplete batches are excluded from rates but their observed latency is retained. Fetch and acknowledgment latency also includes requests initiated inside the window that finish during shutdown. No shutdown completions add to measured throughput.

[HdrHistogram](https://github.com/HdrHistogram/HdrHistogram) records nanosecond observations with three significant digits. Reports include p50, p99 and p99.9; the headline percentile is the maximum of the separate produce, fetch, acknowledgment and scheduled-completion percentiles, so fast fetch requests cannot dilute slow produces. Raw captures retain the separate p99 values. These are request/scheduled-completion latencies, not per-record end-to-end delivery latencies.

## Qualification and targets

These 1 KiB targets assume broker and clients share approximately 4–8 vCPU and SSD storage:

| Metric | In-memory | File |
| --- | ---: | ---: |
| Sustained target | 500,000 records/s (~488 MiB/s) | 250,000 records/s (~244 MiB/s) |
| Full CI minimum | 200,000 records/s | 150,000 records/s |
| Stretch | 1,000,000 records/s | 500,000 records/s |
| p99 at 50% load | <5 ms | <10 ms |
| p99 at 80% and 100% load, and ramp | <50 ms | <100 ms |

A qualifying window lasts at least 30 seconds, consumes at least 95% of produced records, has zero rejected requests/errors/timeouts, and uses at most 85% average CPU and 1 GiB peak heap. Heap stability is defined as a least-squares slope no greater than 1 MiB/s over roughly 250 ms samples, a noise allowance for GC sawtooth; the raw slope is reported. Backlog at the start/end and peak sampled backlog are reported independently. In-memory admission pauses at half the retained-log budget; the wait is included in produce latency and no quota rejection is hidden or retried.

A mode's published sustained rate is the median of the three qualified knees, and all three repetitions must also pass all fixed-load stages. If any repetition cannot qualify, the gate fails. The full launcher automatically enforces the 200K/150K gates for the 1,024-byte payload. Other payload sizes are comparisons, not applications of a 1 KiB threshold. Missing stages, duplicate runs, non-finite metrics, incorrect offered rates, mixed payloads and incomplete captures fail validation. Reports are saved before gate evaluation, so a failed gate retains its evidence.

File durability is **force each group commit before acknowledgment**: `GroupCommitMessageLog` batches writes and `FileMessageLog` calls `FileChannel.force(true)` for each touched segment before acknowledging success. In-memory storage is volatile. The report records filesystem/media information; an unknown or virtual disk is labeled as such rather than assumed to be SSD. File mode on tmpfs measures the file-code path in RAM.

`comparison.txt` and `comparison.json` retain per-concurrency medians and ranges for records/s, payload MiB/s, latency, backlog and resource usage, plus records/s per allocated CPU core. Hardware information includes CPU model/vCPU allocation, RAM, data filesystem/media and the enforced budget. Each raw capture retains its repeat number and explicit workload configuration. Runs that stop early are not averaged as if later stages had completed.

## Resource budgets

The Python launcher detects CPU affinity, ancestor cgroup CPU quotas, available RAM, ancestor memory limits/usage, CPU model and free space on the data filesystem. `resources.json` records this snapshot beside every capture. The `host` resource profile allocates 80% of available CPU/RAM by default. `--fraction` changes that shared fraction; `--cpu-fraction`, `--memory-fraction` and `--disk-fraction` override it separately. CPU quotas round down to 1% of a core. `--profile github-private` caps the allocation at two CPUs/8 GiB; `github-public` caps it at four CPUs/16 GiB. These are reproducibility ceilings, not guarantees of runner hardware or disk speed.

A transient systemd service enforces `CPUQuota`, `MemoryMax`, `MemoryHigh`, disabled swap and process-tree cleanup. Non-root users need passwordless sudo for `systemd-run`. WSL needs systemd enabled. There is no fallback to an unbounded run; resource-limit kills fail the benchmark.

Maven receives 512 MiB heap and Surefire 256 MiB within the envelope. Workers reserve headroom for direct buffers, native allocations and charged file cache. The sustained worker's default heap is capped at 1 GiB, and retained log memory at one quarter of heap. `--heap-mib`, `--direct-mib` and `--memory-log-mib` allow explicit allocations subject to validation. `--data-directory` chooses the temporary data filesystem. File stages check free space against the configured reserve every 250 ms while measuring, then delete only their own temporary data. This is a sampled reserve check, not a disk quota.

All workers use `-XX:+UseZGC -XX:+UseStringDeduplication` and verify these settings at startup. `--stage-millis`, `--warmup-millis` and `--repeats` configure timing within profile validation. The defaults remain 1,024-byte records and 256-record batches in Java; the launcher compares 256/512/1024/4096-byte payloads by default.

For direct Windows smoke testing (without the Linux cgroup envelope):

```powershell
.\mvnw.cmd -pl setu-benchmark -am '-Dtest=SustainedCriteriaTests,SustainedThroughputBenchmarkTests' '-Dsetu.benchmark.max.concurrency=2' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

## CI and publication

Routine CI runs unit tests, integration tests, bounded chaos, isolated steady checks and the short sustained smoke profile. Enable **full_benchmark** in workflow dispatch to run eight full jobs: four payloads times two storage modes, each with three independent repetitions. Fail-fast is disabled so other reports survive a failed gate. The original four-concurrency full ramp estimate is 64 minutes of aggregate measurement/warmup time; fixed-load latency adds another 48 minutes, and adaptive warmup/setup add overhead. A smaller CPU budget reduces the concurrency count; CI shards run in parallel on separate runners and record their own hardware.

Only a successful full run on the default branch can publish badges. `scripts/publish-sustained-badges.py` validates three complete 1 KiB repetitions for each mode and checks all gates before modifying the README or badge JSON. Badges show target, median sustained rate and percentage of target. There is no invented initial measurement: until a full run qualifies, badges say **sustained unverified**. Historical peak/capacity JSON remains available for traceability. Local comparisons never publish automatically.

All eight metric badges use committed SVG images generated from the JSON snapshots, so viewing them needs no external badge service or public access to the repository. The two sustained badges and six historical diagnostics have separate README blocks; updating either set preserves the other. Refresh the images and links without running benchmarks with `python3 scripts/render-benchmark-badges.py`. Publishers update JSON and SVG together, and unchanged results do not rewrite files.

Each sustained comparison also saves `optimization.md`, including evidence from failed qualification stages. CI includes it in the job summary. The review points to measured consumption/backlog, CPU, heap, warmup and latency constraints and suggests follow-up experiments. Reanalyze an existing capture without rerunning the workload:

```bash
python3 scripts/sustained_report.py --input logs/benchmarks/resources/<run>/comparison.json
```

Full-profile artifacts retain reports, raw captures and worker diagnostics. Smoke artifacts exclude temporary broker data files to avoid uploading the benchmark's retained payloads.

## Diagnostic and chaos profiles

The legacy capacity and payload-comparison tools remain available with their existing timings:

```bash
# Historical bounded fanout comparison; does not update sustained badges.
python3 scripts/run-benchmarks.py --kind sweep --repeats 3 --max-concurrency 8

# Explicit exhaustion diagnostic (never part of routine CI or sustained publication).
python3 scripts/run-benchmarks.py --kind capacity --payload-sizes 1024

# Separate broker/client JVMs, fixed 10K/s workload, one/four partitions.
python3 scripts/run-benchmarks.py --kind steady --payload-sizes 1024

# Historical large-batch file experiment.
python3 scripts/run-benchmarks.py --kind large-batch --payload-sizes 1024
```

`ProgressiveCapacityChaosTests` is the historical failure-seeking diagnostic: default two-second warmup/stages, eight-stage floor, 256-record batches, ten-second CPU observation holds, and continued doubling to a runtime limit. It counts consumer fanout and uses a coarse latency histogram; its peaks must not be used as sustained target progress. Properties include `setu.benchmark.min.stages`, `setu.benchmark.max.concurrency`, `setu.benchmark.cpu.hold.millis`, and `setu.benchmark.jfr`. Its JFR recordings, allocation estimates, GC overlap, sampled hot methods and backpressure diagnostics remain under `logs/benchmarks/capacity/`. The old `update-benchmark-badges.py` updates only historical badges and saves its detailed table to `benchmark-badges/capacity-details.md`; CI no longer calls it.

Bounded chaos tests cover actual TCP throughput, recovery, record-size limits, malformed frames, consumer expiry and concurrent progress. `QuickBrokerChaosBenchmarkTests` uses sixteen producers/consumers, 20,000 records and 256-record batches; `BrokerLimitsChaosTests` uses 16,000 records with five-minute workload deadlines. Functional integration requirements remain in `setu-integration-tests`.

```powershell
.\mvnw.cmd -pl setu-benchmark -am '-Dtest=*ChaosTests,!ProgressiveCapacityChaosTests,QuickBrokerChaosBenchmarkTests' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Run report/parser regressions with `python3 -m unittest discover -s scripts -p 'test_benchmark*.py'`. CPU sampling, warmup convergence, concurrency limits and sustained qualification are covered by Java benchmark helper tests. Short local checks validate mechanics, not the throughput targets.
