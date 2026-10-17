# TODO

- [ ] **Fix intermittent FILE baseline latency failures on CI.**

  The Linux file-force change in `123a1d5b067cdadc3c349f08ce8930612c5b4a1a`
  removed the duplicate mapped-file flush, but the strict CI baseline still fails
  a measured latency window. Investigate the remaining stall and verify the fix
  on the hosted runner.

  **Evidence:** [CI run 38947239551](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38947239551),
  [failed FILE baseline job](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38947239551/job/117130117347),
  [FILE measurements and JFR artifact](https://github.com/RishabhRathi-Dev/Setu/actions/runs/38947239551/artifacts/12067726785).
  Download artifacts before their 14-day retention expires.

  - Repeat 1 at 50% load (40,000 records/s): scheduled-completion p99 was
    **404.226 ms**, exceeding the **10 ms** limit. Produce-request p99 was
    **9.527 ms**; the failure is visible in scheduled completion.
  - JFR recorded an approximately **383 ms file-force call inside that window**.
    This is evidence of a long force interval; it does not by itself distinguish
    storage latency from scheduling or host interference.
  - The other eight windows passed, including all three full-load windows at
    **79,991 records/s**, with worst full-load p99 **6.054 ms**. The IN_MEMORY
    baseline, unit tests, integration tests and AOT validation passed.
  - Local Linux before/after baselines both qualified, so local success alone
    does not reproduce or resolve the hosted-runner failure.

  **Investigation and implementation:**

  - [ ] Correlate the failed window's force events, scheduled latency, GC,
    compilation, warmup transition and available host/resource data. Establish
    whether this is a storage stall, scheduler delay, incomplete warmup, or a
    combination; retain measurements from successful and failed repetitions.
  - [ ] Build a repeatable reproduction or a controlled delayed-force experiment
    that explains the scheduling backlog and recovery. Start with
    `ChronicleFileSync`, `SustainedThroughputWorker` and `SustainedCriteria`.
  - [ ] Implement and test a fix for the demonstrated cause. Preserve forcing of
    every touched file before acknowledgment, offered-load pacing and scheduled
    latency accounting. Keep the 80,000 records/s target and qualification limits;
    do not mask the failure by dropping slow samples or retrying until green.
  - [ ] If runner/storage configuration must change, document the measured reason
    and validate the new environment explicitly.

  **Acceptance criteria:**

  - [ ] Run the full FILE baseline on CI with the existing 1.6-core CPU budget,
    5 GiB memory limit, 128 MiB worker heap, 1 KiB records, 256-record batches,
    at least 10s warmup and 30s measurement. All nine windows across three
    independent repetitions must qualify at 50%, 80% and 100% load.
  - [ ] Add meaningful regression coverage for the demonstrated cause and retain
    durability/recovery coverage. Confirm the IN_MEMORY baseline, unit tests,
    integration tests and AOT validation remain green.
  - [ ] Link the passing CI run and retained comparison/JFR evidence here before
    marking this item complete. Record any intentionally skipped workflow jobs.

  **Implementation and evidence (2026-10-17, verification in progress):**

  - The original FILE and IN_MEMORY artifacts are retained under
    `logs/pipeline-check-123a1d5/`. In FILE repetition 1, both partition forces
    start at 18:28:10.542 UTC and last 383 ms, followed by simultaneous 154 ms
    forces. The failed measured window starts at 18:28:01.940 UTC. The longest
    GC pause in the entire recording is 0.137 ms; no recorded long compilation
    overlaps these forces, and the nearby major GC has only 0.055 ms of pauses.
    This supports a shared I/O wait; the capture does not contain kernel I/O
    latency or cgroup throttle counters to rule out host interference.
  - `ChronicleFileSync` now keeps full metadata forcing for the first force and
    file size changes, using Linux `fdatasync` for same-size writes. This avoids
    waiting for timestamp-only ext4 journal transactions, while forcing mapped
    payloads, native indexes/headers and roll metadata before acknowledgment.
    [Linux contract](https://man7.org/linux/man-pages/man2/fsync.2.html),
    [ext4 implementation](https://github.com/torvalds/linux/blob/master/fs/ext4/fsync.c).
    This addresses an unnecessary journal dependency; the old capture alone does
    not prove it caused the hosted stall.
  - Existing tests and qualification criteria are unchanged. The separate
    `scripts/diagnostics/FileForceProbe.java` verifies barriers via JFR, growth
    and old-header rewrites through a separate descriptor, failure after close,
    and retained scheduled latency under a controlled 383 ms force delay.
    Local Linux: barriers `[true, true, false, true, false]`; request p99
    **3.431 ms**, scheduled p99 **356.800 ms**, final scheduled latency
    **1.012 ms**. All 500 samples are retained in percentile calculations;
    pacing is never reset after the delay. This explains backlog/recovery,
    rather than asserting an artificial delay is the original storage cause.
  - Windows unit/integration tests and all 41 Python benchmark-helper tests pass.
    Linux before/after full baseline and hosted CI results are pending. Keep this
    item open until the hosted acceptance criteria and comparison are recorded.
