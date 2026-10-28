# Local FILE I/O comparison

This Linux x86-64 experiment compares write/force implementations through
Setu's real TCP broker and synchronous clients. The default series uses the
first four candidates below; `--owned` and `--parity` include Setu's package.
It builds isolated classpath
overlays; none of these adapters or native libraries enter production artifacts.
See [the benchmark report](../../../docs/file-io-benchmarks.md) for the
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

For the [Setu-owned FFM package](../../../docs/file-io.md), add `--owned` to
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
