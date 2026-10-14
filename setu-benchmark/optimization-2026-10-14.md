# JFR optimization and local node-target verification — 14 October 2026

Three independent repetitions passed at each of 50%, 80% and 100% of the 500K/s in-memory and 250K/s durable-file offered targets. Each window measured 30 seconds after at least 10 seconds of adaptive warmup. Actual rates below include whole-batch and window-boundary effects.

Host: AMD Ryzen 5 7600X (6 physical cores / 12 logical processors), approximately 31.1 GiB RAM, Windows NTFS, Java 26.0.2.1. Broker and TCP load clients shared each worker. JVM ActiveProcessorCount was eight; no OS CPU quota was enforced. Payloads were 1 KiB in 256-record batches. Target workers used 256 MiB heap in memory and 128 MiB with file storage, 128 MiB maximum direct memory and a 64 MiB retained-log budget. File acknowledgment still follows force(true) of every touched segment per group commit.

## Target-load results

| Mode | Pairs | Offered/s | Median actual/s | Actual range/s | Worst p99 ms | Worst CPU % | Peak heap MiB | Min consume ratio |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| IN_MEMORY | 1 | 500,000 | 499,985 | 499,977–499,994 | 2.041 | 15.7 | 246 | 1.000000 |
| FILE | 2 | 250,000 | 249,993 | 249,993–249,993 | 8.057 | 18.2 | 122 | 1.000034 |

All 18 measured windows passed the existing consumption, error, scheduled-latency, CPU, heap and warmup criteria. These are direct target checks on this host; published sustained badges still require the separate resource-enforced capacity profile and its three qualified knees. The raw measurements include missed scheduled records and actual throughput; passing health allows at least 95% of offered load.

## JFR findings and changes

- The baseline at commit `41a5fc9` spent most sampled in-memory CPU time in fetch response encoding and produce decoding. Large frame arrays dominated estimated allocation. File append encoding and consumer-expiry stream construction appeared in file-mode CPU samples.
- A serialized client batch encoder now reuses its exact-size wire frame up to 1 MiB, with larger frames transient and scratch cleared on close. Decoded broker payloads keep independent ownership.
- Topic validation scans allowed ASCII characters directly. ASCII request identifiers bypass decoder construction; non-ASCII strings retain strict UTF-8 validation.
- Consumer expiry iterates pending acknowledgments directly. A single-request group commit reuses its immutable value list; multiple requests allocate one correctly sized merged list.
- File position arrays retained eight bytes per record, growing approximately 1.9 MiB/s at 250K/s before array-capacity overhead. Uniform-length file logs now calculate record positions arithmetically and materialize the original long-array index only when record sizes vary. Uniform batches also publish count and file length without a second index-writing pass. Recovery detects the same layout without changing the disk format.
- A 768 MiB diagnostic heap failed heap-slope qualification in some windows. Two file attempts at 256 MiB also failed heap slope; their third repetition was stopped to restart. The first 128 MiB file attempt exhausted its heap while the per-record index was still present. After compacting that index, using 256 MiB for memory, 128 MiB for files and a 64 MiB log budget gave all final repetitions stable heap under the existing 1 MiB/s slope limit. Earlier failed and interrupted attempts remain in the raw snapshot.

## Matched short profiles

Each before/after point used 5-second warmup and 10-second measurement, eight JVM active processors, 768 MiB heap, 256 MiB log budget, 1 KiB records, 256-record batches and JFR enabled. These measurements cover the protocol, client and queue changes before the subsequent compact file-index optimization. These single-run comparisons are diagnostic evidence; they do not establish a sustained capacity improvement.

| Mode / pairs | Before records/s | After records/s | Change | Estimated allocation bytes/record before | After | Reduction |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| IN_MEMORY / 1 | 1,111,168 | 1,182,029 | +6.38% | 5,653 | 4,558 | 19.38% |
| FILE / 2 | 448,845 | 466,611 | +3.96% | 6,906 | 5,783 | 16.26% |

JFR allocation weights are sampling estimates. Allocation formerly attributed to produce-batch frame creation disappeared from the large allocation sources. Fetch-response encoding and immutable payload copies remain the main candidates for further profiling.

## Evidence and reproduction

- [Raw measurement snapshot](optimization-2026-10-14.json) includes the matched profiles and all target-stage measurements, warmups and capture locations.
- Local worker logs and JFR recordings are retained under `logs/benchmarks/node-target/`. The matched captures and detailed stack summaries are under `logs/optimization/`.
- Run the opt-in command in the [benchmark README](README.md#direct-node-target-verification). It starts three independent workers per mode and fails if any target-load window fails health checks.

Validation passed: 114 Java unit/integration/helper checks, 27 Python benchmark-report checks, and six independent target workers covering all 18 measured windows. Coverage includes protocol ownership after buffer reuse, changing batch sizes, oversized transient frames, strict UTF-8, both storage modes over TCP, TLS, consumer expiry, file recovery and group-commit ordering/failure behavior.
