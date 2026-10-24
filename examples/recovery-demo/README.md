# Failure and recovery demo

This self-contained project uses real TCP connections and a separate broker JVM. It creates fresh data under `examples/target/recovery-demo-<id>/`, preserving evidence after the run. It never connects to an existing broker or removes local data.

Requires JDK 26 and Python 3.10+. From the repository root:

```powershell
python examples/recovery-demo/run.py
```

The same command works on Windows and Linux/macOS (use `python3` if appropriate). It builds the server with the Maven Wrapper, compiles the example, and runs it. `--skip-build` reuses a previously packaged server. No third-party Python packages or database are required.

## What happens

1. Create `orders` and `enriched`, join a managed consumer, and produce one order.
2. Fetch the input with its assignment generation.
3. Submit a consume/produce transaction through a one-request proxy. The broker commits, but the proxy discards its successful reply.
4. Retry with the same producer identity. Verify offset zero and only one output.
5. Request a checkpoint through a local demo-only file signal, then forcibly terminate the broker process.
6. Restart from the same data, retry the transaction again, and verify output count one and committed input offset one.

Expected output includes `DEMO PASSED`. Any failed invariant exits nonzero. Inspect `broker.log`, `metrics.txt`, `environment.json` and `result.txt` in the printed evidence directory. The short-lived broker exposes `/ready` and `/metrics` on an ephemeral loopback port recorded in `operations`. Metrics are also saved before termination.

The intentional lost reply logs an `EOFException` before the successful retry; that is expected failure-injection evidence. The final assertions determine whether the demo passed.

The transaction couples one broker output partition and one input offset. It does not make an HTTP call or database effect atomic. For database effects, the separate [PostgreSQL outbox/inbox guide](../../docs/postgres-outbox.md) and its CI-backed integration suite demonstrate application rollback, publication redelivery, and atomic inbox/business SQL.

## Contention diagnostic

```powershell
python examples/recovery-demo/run.py --skip-build --contention
```

This compares two ordinary writers, two identified writers, and one of each, on separate partitions. Each writer defaults to 20 warmup operations and 100 measured synchronous single-record operations with 1 KiB records. Use `--warmup` and `--operations` to change these counts. JSON lines report elapsed time, completion throughput, per-writer p99 and aggregate broker gate wait. The fair broker-wide write gate preserves transaction visibility; the diagnostic measures contention between writers on different partitions.

The probe allows 30 seconds for warmup and 90 seconds per measured writer; the runner allows 300 seconds overall. Large samples or slow durable storage may exceed these bounds and exit nonzero. Partial output and errors remain in `result.txt`. The small default sample is a functional diagnostic; its p99 is not a stable tail-latency estimate.

This is one closed-loop diagnostic repetition with unenforced resources, not an offered-load benchmark. The environment file records the exact source revision, dirty state, JDK, host and command; filesystem/media is explicitly unknown. Identify that separately and run the qualified benchmark profiles before making capacity claims. This probe measures identified writes; it does not establish the throughput of consume/produce transactions.
