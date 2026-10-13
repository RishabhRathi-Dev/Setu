# Lock-free storage comparison

Local measurements on 12 October 2026 compare commit `0425cff` with the lock-free storage and broker changes in this checkout. These are diagnostic results, separate from the generated CI badges and published capacity tables.

## Scope and correctness

- Memory batches publish their chunks, index, and contiguous offset range with one CAS. Fetches read immutable snapshots while append, acknowledgement reclamation, timed retention, and close proceed.
- The file writer uses a bounded MPSC queue whose CAS combines admission, queue publication, and outstanding accounting. Detached stacks are reversed to FIFO groups. Flushes remain durable before acknowledgement; both active and queued writes consume capacity.
- Memory broker fetches no longer hold the membership/long-poll lock while materializing results. Memory producers avoid wakeup locking when no long poll is registered and leave member-driven reclamation to acknowledgement/removal.
- Membership bookkeeping, waiting for durability, long-poll parking, file-fetch scheduling, and the file delegate still require coordination. The entire broker is not lock-free. File fetches and producer completion retain their existing scheduling boundary to prevent ingestion gains at the expense of consumer progress/latency.

The validation run passed production unit tests, TCP/mTLS integration tests, isolated steady-load tests, and bounded chaos/recovery tests. New tests exercise contiguous concurrent batch offsets, immutable stalled-fetch snapshots, repeated append/close races, FIFO group ordering, active-flush byte/request limits, injected flush failures, interrupted callers, and racing long-poll registration.

## Matched comparison method

The existing `ProgressiveCapacityChaosWorker` runs in fresh JVMs with Java 26.0.2.1, Windows 11, 12 available processors, a 768 MiB heap, ZGC, and string deduplication. Payloads are 1,024 bytes and produce batches contain 256 records. Each run warms up for five seconds, then measures five seconds at each of 1, 2, 4, 8, and 16 producers and consumers. The original three repetitions alternate baseline and changed implementations. Final file scheduling was remeasured in three later repetitions against the saved baselines; host/time drift is a limitation of that comparison. JFR is disabled in these comparisons. Existing benchmark defaults and CI publication remain unchanged.

The baseline overlays only the original `InMemoryMessageLog`, `GroupCommitMessageLog`, and `DefaultBroker` classes from `0425cff`; the worker, transport, client, protocol, JVM settings, and host are shared with the changed run. Each child has its own disposable data directory. Both broker and clients execute in the child JVM, and consumers broadcast-read the same records, so aggregate consume/s is not unique delivered records/s. Five-second samples are still shorter than the deferred long benchmark profile and cannot establish sustained target attainment or statistical significance.

Raw captures and the exact local comparison scripts are under `logs/lock-free/`: `compare.ps1` runs the full comparison, and `repeat-file.ps1` remeasures the final file scheduling behavior against the saved baseline. Earlier file captures are preserved as `unrestricted-fetch.log` and excluded from the table. The initial two-second Maven captures are `lock-free-baseline.log`, `lock-free-after.log`, and `lock-free-final.log`; the full validation output is `lock-free-validation.log`, with final targeted checks in `lock-free-final-validation.log` and `lock-free-scheduling-validation.log`. These generated logs are ignored by Git.

## Matched results

All rates are records/s. Parentheses show the minimum and maximum across three runs; the central value is the median. P99 is the median of the worker's approximate request-histogram p99 values.

| Mode | Producers/consumers | Baseline produce/s | Changed produce/s | Change | Changed aggregate consume/s | Changed p99 (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| IN_MEMORY | 1 | 1,025,998 (1,022,857–1,055,661) | 1,019,401 (937,898–1,032,225) | -0.6% | 994,722 | 0.524 |
| IN_MEMORY | 2 | 879,239 (870,611–898,987) | 875,899 (833,737–902,899) | -0.4% | 1,704,401 | 1.049 |
| IN_MEMORY | 4 | 693,584 (659,721–709,121) | 658,213 (649,595–708,251) | -5.1% | 2,607,541 | 1.049 |
| IN_MEMORY | 8 | 425,654 (413,928–437,008) | 434,330 (428,087–440,041) | +2.0% | 3,331,780 | 2.097 |
| IN_MEMORY | 16 | 251,314 (244,145–254,931) | 251,068 (216,613–253,686) | -0.1% | 3,736,272 | 4.194 |
| FILE | 1 | 118,433 (116,617–121,121) | 119,989 (118,688–120,568) | +1.3% | 119,989 | 4.194 |
| FILE | 2 | 380,460 (372,438–385,730) | 378,546 (375,200–380,639) | -0.5% | 574,244 | 2.097 |
| FILE | 4 | 499,467 (487,414–511,443) | 504,125 (503,740–515,186) | +0.9% | 558,500 | 4.194 |
| FILE | 8 | 553,746 (536,657–556,997) | 556,497 (552,929–561,502) | +0.5% | 566,336 | 8.389 |
| FILE | 16 | 639,515 (623,405–643,355) | 639,125 (634,215–642,463) | -0.1% | 544,222 | 16.777 |

The highest changed median produce rate for IN_MEMORY is 1,019,401 records/s, 10.2% of its 10,000,000 records/s objective in this profile.

The highest changed median produce rate for FILE is 639,125 records/s, 63.9% of its 1,000,000 records/s objective in this profile.

Neither storage mode establishes a material throughput improvement in this profile. At 16 file producers/consumers, median aggregate consume/s is 545,198 before and 544,222 after, while median approximate p99 remains 16.777 ms. Keeping file-fetch scheduling avoids the consumer-progress/latency regression seen with unrestricted file fetches. Neither target is reached, and these short measurements do not establish sustainable end-to-end capacity.

## Profiling

A separate five-stage JFR capture used the existing two-second measurement duration, before restoring file-fetch scheduling. The hottest sampled application methods were `MessageValue.readFrom` and, at higher memory-mode concurrency, `ProtocolCodec.encodeFetchResponse`. Total sampled allocation increased with broadcast fetch work; these recordings include the clients as well as the broker. The recordings are under `logs/benchmarks/capacity/` and their summaries are in `lock-free-final.log`. Profiling overhead and duration differ from the matched comparison, so those rates are not used in the before/after table.

Payload decoding, response encoding, broadcast fan-out, allocation/copying, and serialized file I/O remain candidates for further profiling and performance work. A lock-free publication path alone does not demonstrate the README objectives of 10 million memory records/s and 1 million file records/s.

The publication and parking primitives follow the Java 26 contracts for [AtomicReference](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/util/concurrent/atomic/AtomicReference.html) and [LockSupport](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/util/concurrent/locks/LockSupport.html).





