# Operational visibility

Set `operations.port=9093` in the standalone properties file to enable a read-only HTTP endpoint on **127.0.0.1**. `-1` disables it (default); `0` chooses an ephemeral port printed at startup. Remote binding is deliberately unsupported: the endpoint has no authentication. Scrape locally or use an authenticated forwarding layer operated by your deployment.

- `GET /ready`: 200 only while the transport is running and the broker is open and has not entered a failed journal state; otherwise 503.
- `GET /metrics`: Prometheus text exposition. Other methods are rejected. No administration, compaction, or message payload endpoints are exposed.

Embedding applications can wrap their broker in `BrokerMetrics`, pass that wrapper to `BrokerRequestHandler`, and create `OperationsServer` with the transport's `isRunning` supplier. The application retains ownership of the broker and closes the operations server first. The HTTP executor has two workers and a bounded queue.

| Metric | Meaning |
| --- | --- |
| `setu_requests_total{command,error}` | Dispatched broker outcomes, including admission rejection as `QUOTA_EXCEEDED` |
| `setu_request_duration_seconds` | Fixed-bucket histogram by command; includes long-poll waiting |
| `setu_gate_wait_seconds_total` | Aggregate wait entering the FILE gate for non-fetch dispatched operations |
| `setu_outstanding_write_bytes`, `setu_outstanding_write_requests` | Partition writer admission, including active flushes |
| `setu_partition_flush_nanoseconds_total`, `setu_partition_flushes_total` | Full partition append/force time and attempts, including failures; excludes the metadata journal |
| `setu_member_lag_records` | Sum of retained members' end-minus-committed offsets; managed partitions counted only for their owner |
| `setu_active_consumers`, `setu_retained_memory_bytes` | Active members and retained-log accounting |
| `setu_journal_bytes`, `setu_journal_compactions_total` | Journal logical file sizes and successful compactions since process start |
| `setu_recovery_seconds`, `setu_ready` | Startup recovery elapsed time and broker health |

Counters reset on restart. Gauge scrapes are approximate concurrent samples, not an atomic broker snapshot. Labels are fixed protocol enums; topic names, producer IDs, member IDs and payloads are never metric labels. Dispatched request metrics exclude malformed frames, transport admission and authorization denials that occur before the broker. Logs remain the source for those failures. Flush duration includes append work, not solely `FileChannel.force`; use the existing JFR force diagnostics for that breakdown.

Example queries: `rate(setu_requests_total[1m])`, `histogram_quantile(0.99, sum by (le, command) (rate(setu_request_duration_seconds_bucket[5m])))`, and `rate(setu_gate_wait_seconds_total[1m])`. Compare request latency and gate wait with outstanding writes before changing transaction synchronization.

The [mixed-write diagnostic](../examples/recovery-demo/README.md#contention-diagnostic) compares ordinary, identified and mixed traffic on separate partitions. It records local evidence without claiming sustained capacity. Existing resource-enforced benchmark profiles and qualification criteria remain authoritative for performance claims.
