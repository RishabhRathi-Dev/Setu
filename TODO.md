# Project TODO

- [x] Add a longer benchmark profile when performance work resumes:
  - Warm up for 10 seconds at each measured concurrency, then measure for 30 seconds.
  - Run three independent repetitions per payload, storage mode and concurrency; extend warmup if throughput has not stabilized.
  - Report median/range for records/s, payload MiB/s and p99 latency, alongside consumer backlog and resource usage.
  - Split the full comparison across CI jobs and retain a short smoke profile for routine checks; the full sweep is approximately 64 minutes before overhead.
  - Resumed: the sustained full profile uses these timings; smoke and legacy diagnostic timings remain short.

Implemented in `SustainedThroughputWorker`, `scripts/run-benchmarks.py`, and the CI full-profile matrix.
Reports now use unique delivery, HDR percentiles, resource-aware concurrency, fixed offered loads,
and qualified sustained badges. See `setu-benchmark/README.md` for the exact criteria and commands.
The targets below remain goals; implementing the measurement and gates does not establish that they have been achieved.


**Assumption:** broker, server and load client share one box (about 4-8 vCPU, SSD, 1 KiB payloads). Because the client burns CPU too, the right target is roughly half of what the broker could do alone.

## Define the target first

A target should only count if all of these hold for at least 30 seconds:

- Consume rate is at least 95% of produce rate (the backlog isn't growing).
- Zero errors or timeouts.
- p99 is measured, not a bucket bound, at the target load.
- Heap is stable (no upward trend over the window).
- CPU is at most 85% average, so there's headroom.

Your current "peak" fails the first test in both modes: in-memory consumes 100% of nothing at high concurrency, and file mode consumes about 28% of what it produces in the hold.

## Targets per mode

| Metric | In-memory | File |
|---|---|---|
| **Today (sustained, honest)** | ~200-250K/s, ~95% consumed | ~200K/s produced, ~55K/s consumed |
| **Gate (fail CI if below)** | 200K/s | 150K/s with consume ≥95% |
| **Target** | **500K/s** (~490 MiB/s) | **250K/s** (~245 MiB/s) |
| **Stretch** | 1M/s (~1 GiB/s) | 500K/s (~490 MiB/s) |
| Consume ratio | ≥ 95% | ≥ 95% |
| p99 at 50% of peak load | < 5 ms | < 10 ms |
| p99 at peak load | < 50 ms | < 100 ms |
| Avg CPU at target | ≤ 85% | ≤ 85% |
| Producers/consumers | 8-16 each | 8-16 each |
| Heap | flat, ≤ 1 GB | flat, ≤ 1 GB |

## Why these numbers

- **In-memory 500K/s:** you already hit 434K for a second and drain at 800-870K/s, so the broker core can do it. Reaching it sustained means fixing the producer-side contention and the stall where produce drops to 0, not new hardware.
- **File 250K/s:** you plateau at about 200K/s with CPU at 52-62%, which suggests an I/O or flush bottleneck. Moderate gains are realistic with batching, larger sequential writes, or a looser fsync policy. Beyond about 500K/s you'd be fighting the disk, since CI disks often give only 200-500 MB/s.
- **Drop the 10M/s and 1M/s targets.** 10M/s of 1 KiB is about 10 GB/s, which is multi-node or memory-bandwidth territory.

## Change how the stages work

1. **Find the knee, not the crash.** Report the highest rate where consume ≥ 95% and p99 is under the limit. Stop ramping when that breaks, instead of continuing to 2,048 producers.
2. **Cap concurrency at about 2x core count.** 128-2,048 threads on a few cores only measures context switching.
3. **Use fixed offered load for latency.** Run at 50%, 80% and 100% of the knee, and report p50/p99/p99.9 from a real histogram such as HdrHistogram.
4. **State the durability level for file mode** (fsync per batch, per interval, or none). The numbers mean nothing without it.
5. **Record the hardware** (vCPU, RAM, disk type) in the report header and normalize to records/s per core, since CI runners vary.

## Badge

- In-memory: `500K/s target, 250K/s sustained, 50%`
- File: `250K/s target, 200K/s sustained, 80%`

Progress is then measured against what a single node can actually do, and the badge shows sustained throughput instead of a one-second burst.

If you tell me the runner specs (vCPU, disk) and the file-mode fsync policy, I can tighten these numbers.
