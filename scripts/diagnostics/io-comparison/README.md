# Local FILE I/O comparison

This Linux x86-64 experiment compares write/force implementations through
Setu's real TCP broker and synchronous clients. The default series uses the
first four candidates below; `--owned` and `--parity` include Setu's package.
It builds isolated classpath
overlays; none of these adapters or native libraries enter production artifacts.
See [recorded measurements](#recorded-measurements) for the
production baseline and target requirements.

| Variant | Payload write | Durable barrier |
| --- | --- | --- |
| `current` | Reused heap buffer, positional `FileChannel.write` | Existing `ChronicleFileSync` |
| `java-direct` | Reused direct buffer, positional `FileChannel.write` | Existing `ChronicleFileSync` |
| `cpp-uring` | Direct buffer, Java FFM downcall, C++/liburing write | C++/liburing fsync or fdatasync |
| `java-uring` | Direct buffer, jasyncfio asynchronous write, completion awaited | jasyncfio fdatasync; JDK full force on first use/file growth |
| `setu-uring` | Direct buffer, Setu-owned Java FFM io_uring, completion awaited | Setu fdatasync; JDK full force on first use/file growth |

The direct-buffer candidate avoids the JDK's heap-to-native staging copy. Its
scratch-buffer growth and 1 MiB retained-size cap match the current path.
The C++ candidate uses registered file descriptors and an eight-entry ring per
file. The Java library shares one sixteen-entry event loop, configured to sleep
when idle. Neither enables SQPOLL, IOPOLL, O_DIRECT, early acknowledgement, or a
different batch size. Every write completes before Chronicle publishes the
document header; every required force completes before durable success. Offset,
CRC, committed-boundary, group-commit and consumer-journal code stays in place.
This tests substitution in the existing synchronous architecture, not a new
broker with many outstanding asynchronous writes.

## Prerequisites and invocation

Use JDK 26, Python 3.10+, Linux/WSL, systemd and cgroup v2. Put the data directory
on persistent storage; `/tmp` may be tmpfs. Build a separate baseline with the
Maven Wrapper and the repository JVM options. Its production Java sources must
match the working tree. The harness rejects differing sources. Retain a target
run's JVM argument array (JSON) and its `logs/benchmarks/resources/target-*/resources.json`.
The argument array must contain the `NodeTargetThroughputWorker` main class and
an absolute classpath. Select a baseline directory with one resource capture.

The experiment uses the published
[jasyncfio 0.0.8 linux-amd64 JAR](https://repo.maven.apache.org/maven2/one/jasyncfio/jasyncfio/0.0.8/jasyncfio-0.0.8-linux-amd64.jar),
SHA-256 `873435af12c858591146baef0aac441374f53315c38ffd01f356842ee346682c`.
Place it at `WORK/vendor/jasyncfio-0.0.8-linux-amd64.jar`. The native compiler is
`WORK/tools/root/usr/bin/gcc`; liburing headers/libraries are under that same
`usr/include` and `usr/lib`. The recorded run used GCC 15.2.1 and liburing 2.14.
Add the toolchain library directory to `LD_LIBRARY_PATH`. These are experiment
dependencies; no Maven dependency or production packaging change is required.

```bash
export JAVA_HOME=/path/to/jdk-26
export PATH="$JAVA_HOME/bin:$PATH"
repo=/path/to/Setu
base=/path/to/built-baseline
work=/path/to/persistent/experiment
command=/path/to/captured-target-command.json
mkdir -p "$work/vendor" "$work/evidence"
export LD_LIBRARY_PATH="$work/tools/root/usr/lib:${LD_LIBRARY_PATH:-}"

python3 "$repo/scripts/diagnostics/io-comparison/run.py" build \
  --repo "$repo" --base "$base" --base-command "$command" --work "$work"
python3 "$repo/scripts/diagnostics/io-comparison/run.py" verify \
  --repo "$repo" --base "$base" --base-command "$command" --work "$work"
python3 "$repo/scripts/diagnostics/io-comparison/run.py" smoke \
  --repo "$repo" --base "$base" --base-command "$command" --work "$work"
python3 "$repo/scripts/diagnostics/io-comparison/run.py" measure \
  --repo "$repo" --base "$base" --base-command "$command" --work "$work"
python3 "$repo/scripts/diagnostics/io-comparison/run.py" report \
  --repo "$repo" --base "$base" --base-command "$command" --work "$work"
```

The runner uses the same systemd enforcement as `scripts/run-benchmarks.py`;
the invoking account needs permission to create transient system services.
Existing captures are never overwritten. Use a fresh work directory for a new
measurement series. `--tag NAME` preserves separate verification/smoke attempts.
Commands, logs, JFR files, build hashes and the report are retained in
`WORK/evidence/`. The existing workload removes only its own temporary stage
data after closing the broker. Verification directories remain for inspection.

## Interpretation and checks

Use `--production` for the integrated default. This mode compiles only the
workload/measurement helpers: no storage overlays, external io_uring libraries
or backend-selection JVM properties. `verify` uses the production FILE factory;
four `measure` windows last 60 seconds each after adaptive warmup. The report
requires native force events, so an unavailable-kernel fallback cannot be
mistaken for a measured io_uring run. It combines native data forces with JDK
metadata forces. These single-load runs check consistency, not full target
qualification. The comparison modes below remain isolated diagnostic controls.

For the [Setu-owned FFM package](../../../setu-storage/README.md#file-io), add `--owned` to
every invocation and use a fresh work directory. This compares `current` with
`setu-uring`, excludes jasyncfio from compilation/runtime, and skips C++/liburing
entirely; the vendor JAR and native toolchain prerequisites do not apply.
The matching baseline must include the new storage package. Four repetitions
use alternating order: current/Setu, Setu/current, current/Setu, Setu/current.
Current retains heap payload staging; Setu uses its GC-managed direct allocator and retains the
buffer through completion without copying into native slots. The package still
supports copied heap/scoped-buffer input. The report includes mean, median, minimum and maximum
throughput so a slow run remains visible alongside the typical rate.

Use `--parity` instead of `--owned` to compare `current`, `java-uring` and
`setu-uring` in one series. This requires the pinned jasyncfio JAR, but no C++
toolchain or liburing. The four rounds run current/jasyncfio/Setu,
jasyncfio/Setu/current, Setu/current/jasyncfio, then Setu/jasyncfio/current.
Every candidate occupies every position, but four rounds cannot balance three
positions exactly. Both io_uring adapters use direct staging and JDK full force
on first use/growth, with subsequent data-only forces through their rings.
The Setu ring owner remains a platform thread; its public synchronous API is
also exercised with concurrent virtual-thread callers in native tests.

`verify` appends and reads 76,800 records across mapped-file growth, reopens and
checks dense offsets and payload contents/CRC, forcibly kills a child after
5,120 acknowledged records, verifies recovery and appends again. `/dev/full`
checks that a failed write is not reported as successful. This is process-crash
coverage, not a simulated power failure or a complete storage qualification.

`measure` runs four fresh-JVM repetitions per variant, rotating order so every
variant appears once in each position. Every run has at least ten seconds of
adaptive warmup and a thirty-second full-load measurement: two pairs, 1 KiB
records, 256-record batches and 250,000 records/s offered. It inherits the
captured JVM and cgroup limits. The 50% and 80% stages are not run, so even a
healthy full-load stage cannot qualify the target. Smoke runs have two-second
warmup and three-second measurements and are excluded from the comparison.

All variants record the same custom force event because native barriers do not
emit `jdk.FileForce`. `CompareRecording` clips waits to the measured interval
and separates partition and consumer-journal forces. Summed waits may exceed
wall time because threads wait concurrently; these are not disk utilization.
Whole-cgroup CPU supplements JVM CPU to include charged I/O-worker execution.

The stock jasyncfio version hung after completing `VerifyIo`: thread evidence
showed event-loop threads in `io_uring_enter` and non-daemon pool scavengers
remaining after close. The runner records `natural_exit=false` and stops only
that experiment's service after the completed-work/JFR-flushed marker and a
three-second exit grace. Such a run can provide performance evidence but fails
lifecycle qualification. Missing completion markers, request failures, force
failures and timeouts remain errors. Other variants must exit naturally.

PanamaUring development revision
`0613da385d6cfa79dd94f5e1765f228f812bf140` built on this JDK but failed ring
initialization with a `NativeArrayPointer.pointer` layout exception. It was
excluded from timed comparisons; jasyncfio supplies the fourth candidate.
The public APIs were checked against the
[jasyncfio source](https://github.com/ikorennoy/jasyncfio),
[PanamaUring source](https://github.com/dreamlike-ocean/PanamaUring), and
[liburing fsync documentation](https://github.com/axboe/liburing/blob/master/man/io_uring_prep_fsync.3).

## Recorded measurements

The integrated Linux Panama/io_uring default produced a median **183,691
records/s** across four one-minute windows on 28 October 2026. Every run had
zero request/force failures and exited naturally, but consumer backlog grew in
every window. **The 250,000 records/s target remains unmet.** These diagnostics
do not qualify sustained capacity or change published benchmark badges.

The [storage guide](../../../setu-storage/README.md#file-io) describes automatic
selection and durability. Results from different implementations or sessions
are kept separate below.

### Integrated Linux default

These four fresh-JVM measurements used production storage classes, without
storage overlays, backend properties or external io_uring libraries. JFR analysis
requires native force events, so a fallback cannot pass as an io_uring result.
All final windows are included, including the slower first run.

| Run | Produced/s | Consumed/s | Backlog start → end | Peak backlog | CPU/quota |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 175,475 | 174,827 | 512 → 39,168 | 39,424 | 59.31% |
| 2 | 183,151 | 182,754 | 1,792 → 25,088 | 26,368 | 58.39% |
| 3 | 184,819 | 184,589 | 19,968 → 33,536 | 35,584 | 57.34% |
| 4 | 184,230 | 183,774 | 1,024 → 28,160 | 28,416 | 58.91% |

Mean production was 181,919/s; population coefficient of variation was 2.07%.
Median consumption was 183,264/s. No CPU throttling was reported. This supports
repeatability in this sample, not long-duration or cross-platform stability.

Median per-run journal force mean/p99 was 0.840/2.270 ms; partition force
mean/p99 was 1.999/3.323 ms. Median acknowledgement p99 was 4.110 ms; produce
p99 was 4.456 ms. Force waits include queueing and scheduling, not just device
service time. Worst scheduled p99 was 17,784 ms, reflecting the accumulating
offered-load deficit rather than disk latency.

Every window retained `healthy=false` and `qualified=false` under unchanged
gates. Median production misses the target by 26.52%. The 50%/80% load stages
were not run. The four-window default validation does not demonstrate a
throughput improvement over FileChannel.

### Linux backend comparison

The preceding 27 October series used four 30-second windows per backend,
with matching direct staging and first-use/growth metadata barriers for both
io_uring adapters. Order was FileChannel/jasyncfio/Setu,
jasyncfio/Setu/FileChannel, Setu/FileChannel/jasyncfio, then
Setu/jasyncfio/FileChannel. Four rounds cannot balance three positions exactly.

| Backend | Median produced/s | Mean produced/s | Median consumed/s | Produced/s range | CPU/quota |
| --- | ---: | ---: | ---: | ---: | ---: |
| FileChannel | 198,123 | 198,935 | 198,127 | 197,376–202,121 | 63.63% |
| jasyncfio 0.0.8 | 181,692 | 181,664 | 181,909 | 180,804–182,468 | 64.10% |
| Setu-owned Panama | 184,691 | 185,199 | 185,745 | 183,637–187,776 | 58.74% |

| Backend | Round 1 produced/s | Round 2 | Round 3 | Round 4 |
| --- | ---: | ---: | ---: | ---: |
| FileChannel | 202,121 | 198,682 | 197,564 | 197,376 |
| jasyncfio | 181,948 | 180,804 | 181,436 | 182,468 |
| Setu-owned Panama | 185,207 | 183,637 | 187,776 | 184,175 |

Setu's median was 1.65% above jasyncfio and 6.78% below FileChannel in this
sample. All timed processes exited naturally with zero request/force failures;
every window remained unqualified. The stock jasyncfio reference required
termination after separate functional verification because of surviving threads.
Its timed runs exited normally, but the verification lifecycle defect remained.

The earlier four-way experiment on 26 October measured median production of
195,191/s for heap-buffer FileChannel, 195,597/s for direct-buffer FileChannel,
191,983/s for C++/FFM io_uring and 181,769/s for jasyncfio. Late slowdowns reduced
the first three means to 158,270/s, 159,230/s and 159,750/s; jasyncfio's mean was
180,629/s. Run order and changing force latency prevent attributing that
difference solely to the backend. None met the target. These older runs do not
measure the integrated default.

Published results before tracing were already near 200K/s. The available
history therefore does not establish a 50K/s regression caused by tracing.
The [tracked target evidence](../../../benchmark-badges/node-target-results.json)
and its Git history remain unchanged.

### Linux protocol and provenance

| Setting | Value |
| --- | --- |
| Base revision | `cacdbaaba3fd374ac3c20cc1e08c80daa4c73159` plus local FILE changes; exact measured sources retained in manifests |
| Host | AMD Ryzen 5 7600X, 12 logical CPUs; Arch Linux under WSL2 |
| Kernel / JDK | Linux 6.6.87.2-microsoft-standard-WSL2; Oracle Java 26.0.2.1+1-7, ZGC |
| Filesystem/media | Persistent `/var/tmp`, ext4 `/dev/sdd`, ordered data; WSL virtual disk, physical media not independently identified |
| Resource limits | systemd/cgroup v2; 3.2-core quota; 12,506,660,864-byte memory maximum; workload swap disabled |
| JVM memory | 128 MiB heap, 128 MiB JDK direct-memory maximum; bounded 16 MiB FFM scratch within the cgroup |
| Workload | Real TCP, two synchronous producer/consumer pairs, 1,024-byte records, 256-record batches |
| Durability/load | 250,000 records/s offered; 250 microsecond group-commit linger; durable consumer acknowledgements |
| Warmup | Adaptive, minimum 10 seconds; default runs 13.012/11.011/12.012/23.020 seconds; preceding comparison 11.011–17.025 seconds; all reported stable |
| Measurement | Default: four 60-second windows; comparison: four 30-second windows per candidate |

`logs/uring-default-20261027/results/` retains the production-default commands,
logs, JFR, host capture, measured bytecode, source snapshots and SHA-256 checksums.
The measured Linux build is `/var/tmp/setu-uring-default-final-20261028/`.
Comparison evidence is under `logs/uring-parity-20261027/results/`; earlier
four-way evidence is under `logs/io-comparison-20261026/results/`.
Local evidence and runtime data are ignored by Git.

The default series initially rejected a 120-second request before measurement
because the workload caps stages at 60 seconds. This and an earlier shell
line-ending startup failure are retained separately; no measured window was
discarded. After measurement, an error-path guard prevented self-suppression
when failed open and shutdown return the same exception. All 20 native/default
I/O tests passed again; successful I/O behavior was unchanged. Measured and
delivered snapshots remain separate. Subsequent documentation cleanup does not
change those measurements.

### Windows flush experiment

On Windows 11 build 26100 x64, Java FFM successfully queried and created an I/O
ring. Maximum API version was 400, and read/write/flush operations were supported.
The experiment created a version-300 ring and compared the existing JDK force
with `BuildIoRingFlushFile(FILE_FLUSH_DEFAULT)` after the same mapped-view force.
Payloads stayed mapped to preserve Chronicle's coherence requirements.

| Series | Round | JDK flushes/s | Panama ring flushes/s |
| --- | --- | ---: | ---: |
| 1 | 1 | 973.973 | 946.143 |
| 1 | 2 | 971.652 | 962.945 |
| 1 | 3 | 961.513 | 955.947 |
| 1 | 4 | 960.330 | 961.553 |
| 2 | 1 | 951.215 | 952.509 |
| 2 | 2 | 977.601 | 971.774 |
| 2 | 3 | 969.470 | 974.695 |
| 2 | 4 | 968.696 | 980.854 |

Series-one medians were 966.583 and 958.750 flushes/s; series-two medians were
969.083 and 973.235. Both comparisons differ by less than 1%, with the ordering
reversed. These small trials provide no consistent advantage for ring flushes
and do not establish a general performance ordering. Windows retains mapped
writes and JDK durability barriers. Native
ring writes would require a separate storage design because mapped and ordinary
file I/O are not guaranteed coherent. Both platforms retain the same batching,
virtual partition writers and durable acknowledgement semantics.

The experiment ran on the same Ryzen host, Oracle Java 26.0.2.1+1, Windows NTFS
on `D:`, without CPU/memory enforcement; physical storage was not independently
identified. Each candidate used a fresh 64 MiB file, a 256 KiB payload, header
rewrites and a scoped mapped-view force per iteration. Each series used one
fresh JVM and four alternating rounds with two-second warmups and five-second
measurements. File readback,
completion checks and close succeeded. This is a flush microbenchmark, not broker
throughput, a virtual-thread scheduling test or target qualification.

The [Windows probe](../WindowsIoRingProbe.java) reproduces the
second series; invocation is in the [tooling guide](../../README.md#storage-diagnostics).
The initial prototype, capabilities, output, host details and hashes are retained
under ignored `logs/windows-io-20261028/`. API definitions come from Microsoft's
[I/O-ring header](https://github.com/microsoft/win32metadata/blob/main/generation/WinSDK/RecompiledIdlHeaders/um/ioringapi.h)
and [flush modes](https://github.com/microsoft/win32metadata/blob/main/generation/WinSDK/RecompiledIdlHeaders/um/WinBase.h).

### Functional validation

Linux production tests through TCP/mTLS passed, including all 79 storage tests
and native coverage. Windows production tests passed, with five Linux-native
storage tests, one platform-specific core test and two optional PostgreSQL tests
skipped. The Linux run also skipped the two optional database tests.

All 20 native/default I/O tests passed with one virtual-thread carrier. Coverage
includes backpressure, independent completion, mapped-header barriers, setup
fallback, resource errors and shutdown. Benchmark helper/JFR tests passed;
The final pre-push Python tooling run passed all 81 tests.

The integrated default passed 76,800-record growth/CRC/dense-offset/readback and
reopen checks, recovery of 5,120 acknowledged records after process termination,
append after recovery and failed-write rejection. Packaging, the FILE recovery
example and portable force probe passed. Process-crash checks do not simulate
power loss, and smoke captures are excluded from performance conclusions.
