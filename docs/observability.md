# Operational visibility

Set `operations.port=9093` in the standalone properties file to enable a read-only HTTP endpoint on **127.0.0.1**. `-1` disables it (default); `0` chooses an ephemeral port printed at startup. Remote binding is deliberately unsupported: the endpoint has no authentication. Scrape locally or use an authenticated forwarding layer operated by your deployment.

- `GET /ready`: 200 only while the transport is running and the broker is open and has not entered a failed journal state; otherwise 503.
- `GET /metrics`: Prometheus text exposition. Other methods are rejected. No administration, compaction, or message payload endpoints are exposed.

Embedding applications can wrap their broker in `BrokerMetrics`, pass that wrapper to `BrokerRequestHandler`, and create `OperationsServer` with the transport's `isRunning` and `operationalGauges` suppliers. The original three-argument constructor remains supported and omits transport samples. The application retains ownership of the broker and closes the operations server first. The HTTP executor has two workers and a bounded queue. The standalone launcher wires both transport suppliers automatically.

| Metric | Meaning |
| --- | --- |
| `setu_requests_total{command,error}` | Dispatched broker outcomes, including admission rejection as `QUOTA_EXCEEDED` |
| `setu_request_duration_seconds` | Fixed-bucket histogram by command; includes long-poll waiting |
| `setu_produce_response_records_total{command,acknowledgement}`, `setu_produce_response_payload_bytes_total{command,acknowledgement}` | Record count and payload bytes represented by successful produce/transaction broker responses, split by requested acknowledgement mode |
| `setu_fetch_response_records_total`, `setu_fetch_response_payload_bytes_total` | Records and payload bytes materialized in successful fetch broker responses |
| `setu_gate_wait_seconds_total` | Aggregate wait entering the FILE gate for non-fetch dispatched operations |
| `setu_outstanding_write_bytes`, `setu_outstanding_write_requests` | Partition writer admission, including active flushes |
| `setu_partition_flush_nanoseconds_total`, `setu_partition_flushes_total` | Full partition append/force time and attempts, including failures; excludes the metadata journal |
| `setu_member_lag_records` | Sum of retained members' end-minus-committed offsets; managed partitions counted only for their owner |
| `setu_active_consumers`, `setu_retained_memory_bytes` | Active members and retained-log accounting |
| `setu_journal_bytes`, `setu_journal_compactions_total` | Journal logical file sizes and successful compactions since process start |
| `setu_recovery_seconds`, `setu_ready` | Startup recovery elapsed time and broker health |
| `setu_connections_active`, `setu_connections_max` | Accepted sockets currently tracked by the transport and configured connection cap |
| `setu_connections_accepted_total`, `setu_connections_limit_rejections_total` | TCP accepts including rejected sockets, and rejections at the connection-count limit |
| `setu_request_buffer_used_bytes`, `setu_response_buffer_used_bytes` | Transport credit currently reserved, including connection buffering and responses awaiting transmission |
| `setu_request_buffer_max_bytes`, `setu_response_buffer_max_bytes` | Configured transport credit limits |
| `setu_process_cpu_seconds_total` | Process CPU time across all threads; its rate is CPU cores used, not percent of a cgroup budget |
| `setu_process_start_time_seconds`, `setu_process_uptime_seconds` | JVM process start epoch and elapsed uptime |
| `setu_jvm_heap_used_bytes`, `setu_jvm_heap_committed_bytes`, `setu_jvm_heap_max_bytes`, `setu_jvm_nonheap_used_bytes` | MXBean memory samples |
| `setu_jvm_gc_collections_total`, `setu_jvm_gc_collection_seconds_total` | Sums across available collector MXBeans; collection time is not a pause histogram |
| `setu_jvm_platform_threads`, `setu_jvm_available_processors` | Live platform thread count (excludes virtual threads) and JVM-visible processor count |
| `setu_jvm_direct_buffer_bytes`, `setu_jvm_mapped_buffer_bytes` | MXBean buffer-pool memory; does not account for all native memory |

Counters reset on restart. Gauge scrapes are approximate concurrent samples, not an atomic broker snapshot. Labels are fixed protocol enums; topic names, producer IDs, member IDs and payloads are never metric labels. Request outcome series include zero values so the first error is visible to rate queries. Unsupported JVM measurements are omitted. `setu_ready` from the HTTP endpoint checks both broker and transport, matching `/ready`; a direct `BrokerMetrics.scrape()` only knows broker readiness.

Produce response counters include successful identified retries even when deduplication appends nothing. `ACCEPTED` means accepted work, not durable completion; `IN_MEMORY` remains volatile in either mode. Fetch counters count repeated fetches again. These counters are recorded before response encoding/transmission and do not prove that clients received replies or acknowledged records. They measure response traffic, not unique persisted records or end-to-end delivery. Payload bytes exclude protocol/framing overhead.

Dispatched request metrics exclude malformed frames, transport admission and authorization denials that occur before the broker. Logs remain the source for those failures; the connection-limit counter covers that specific transport rejection only. Broker latency surrounds `broker.execute()` and excludes decoding, encoding, socket writes and network time. Flush duration includes append work, not solely `FileChannel.force`; use the existing JFR force diagnostics for that breakdown. Histogram percentiles are bucket estimates; retain client-side HDR histograms and scheduled-load measurements for benchmark qualification.

Example queries: `rate(setu_requests_total[1m])`, `histogram_quantile(0.99, sum by (le, command) (rate(setu_request_duration_seconds_bucket[5m])))`, and `rate(setu_gate_wait_seconds_total[1m])`. Compare request latency and gate wait with outstanding writes before changing transaction synchronization.

The [mixed-write diagnostic](../examples/recovery-demo/README.md#contention-diagnostic) compares ordinary, identified and mixed traffic on separate partitions. It records local evidence without claiming sustained capacity. Existing resource-enforced benchmark profiles and qualification criteria remain authoritative for performance claims.

## Logs and request traces

`BrokerRequestHandler` creates one OpenTelemetry `SERVER` span per dispatch, named `setu.<COMMAND>` after decoding or `setu.request` for undecodable frames. Spans cover decoding, broker execution and response encoding, ending before socket transmission. Attributes contain only `setu.command` and `setu.error_code`; non-success responses and failures set error status. Request payloads, topic/member/producer names and exception messages are not span attributes or events.

Java client calls create `CLIENT` attempt spans and propagate W3C `traceparent`/`tracestate` through the [required request fields](../setu-core/README.md#request-trace-context). Broker dispatch spans are remote children of those attempts, retaining trace ID, sampling flags and valid vendor state. Async calls capture context at submission, including metadata lookup, routed writes and retries. The resulting trace can connect an application span, client exchange and broker execution across process boundaries.

Empty or bounded but invalid W3C parents create fresh server roots. Server extraction starts from an empty context, so a pooled worker cannot supply another request's parent. Context is restored on return, including failures. Request trace metadata is excluded from producer fingerprints and durable journals; records and fetch responses do not carry it. Message-level produce-to-consume propagation and baggage are outside this envelope. Security denials before dispatch do not create broker spans, but the client attempt records the denial. The stable API dependencies start no exporters without an SDK or Java agent. Embedders can inject tracers through `new BrokerRequestHandler(broker, tracer)` and `client.withTracer(tracer)` and own SDK shutdown.

Propagation is mandatory and has no disable option. Every request includes both trace fields, with zero lengths when no valid context exists. Structured broker errors do not trigger retry, preserving uncertain-write behavior. Recording and export still require an SDK or Java agent. See the [client tracing contract](../setu-client/README.md#distributed-request-tracing).

The optional Java agent supplies the SDK, bounded asynchronous export, Logback OTLP capture and MDC trace IDs. Existing console and rolling logs remain active, now with `trace_id`/`span_id` fields. OTLP logs carry native trace/span IDs when emitted inside a request span. INFO does not emit a log for every successful request; enable DEBUG briefly to inspect successful request correlation. Logs for startup, connections or work outside a request may have no trace ID. Existing diagnostic escaping and payload exclusions still apply. Exception logs retain their existing failure details; span attributes do not copy them.

## Local LGTM stack

For an entirely containerized broker and LGTM stack, use the [container guide](containers.md#broker-with-lgtm): `docker compose -f compose.yaml -f compose.lgtm.yaml up --build -d`. Its Alloy sidecar shares the broker network namespace and needs no host installation. The following walkthrough instead runs the broker and Alloy on the host with containerized backends.

The checked-in [Compose configuration](../config/observability/compose.yaml) runs Mimir for Setu metrics plus the pinned Grafana `otel-lgtm` development bundle for Grafana, Loki and Tempo. That bundle also includes its own Prometheus; the **Setu Mimir** datasource and dashboard query Mimir. This is a local development setup with loopback ports and persistent named volumes, not production deployment configuration.

Prerequisites: JDK 26, Maven Wrapper, Docker Compose, and a native [Grafana Alloy installation](https://grafana.com/docs/alloy/latest/set-up/install/) on the **same host/network namespace as the broker**. The configuration uses stable scrape/remote-write components and was validated with Alloy **1.21.2**. Use OpenTelemetry Java agent **2.33.0** with the API **1.67.0** used by this build. The agent is downloaded separately and stays in ignored build output. No credentials or agent binaries are tracked.

From the repository root:

```powershell
.\mvnw.cmd -pl setu-server -am package
.\mvnw.cmd -pl setu-server dependency:copy '-Dartifact=io.opentelemetry.javaagent:opentelemetry-javaagent:2.33.0' '-DoutputDirectory=../target/observability'
docker compose -f config/observability/compose.yaml up -d
```

The dependency-copy output directory is relative to `setu-server`, so the agent lands in the root `target/observability/`. On Bash use `bash ./mvnw` for both Maven commands. The remaining commands work in PowerShell and Bash. Start native Alloy in another terminal (use the installed binary's name/path, such as `alloy.exe` on Windows):

```text
alloy run --server.http.listen-addr=127.0.0.1:12345 --storage.path=logs/alloy config/observability/config.alloy
```

Do not start a second Alloy service on the same ports. If Alloy was installed as a background service, configure that service with the supplied file instead. Alloy must run natively because a container's localhost cannot reach the host's loopback-only operations endpoint. The [scrape configuration](../config/observability/config.alloy) uses instance `local-setu`, matching the agent's resource identity. Change both when collecting multiple brokers; keep each instance identity stable and unique.

Start the dedicated volatile development broker in another terminal:

```text
java @config/jvm.options -javaagent:target/observability/opentelemetry-javaagent-2.33.0.jar -Dotel.javaagent.configuration-file=config/observability/otel-agent.properties -jar setu-server/target/setu-server-1.0.0-SNAPSHOT.jar --config config/observability/server.properties
```

In PowerShell quote the `@config/jvm.options`, `-javaagent:...` and `-Dotel...` arguments with single quotes. This direct launch bypasses any existing AOT cache. It uses a dedicated IN_MEMORY configuration on broker port 9092 and operations port 9093; use free ports if another broker is already running and update the scrape target accordingly. For an existing FILE deployment, enable `operations.port` in its own configuration instead; storage and acknowledgement contracts remain unchanged.

The [agent configuration](../config/observability/otel-agent.properties) sends logs/traces via OTLP/HTTP to `127.0.0.1:4318` and samples 10% of root traces. The parent-based sampler follows valid incoming sampling decisions. Agent metrics export is disabled because `/metrics` is the single JVM/broker metrics collection path. The sampling ratio affects traces, not metric counts or log export. For local correlation checks use `-Dotel.traces.sampler.arg=1.0` on the process that creates the root; unsampled log trace IDs may have no stored trace. Do not enable a second file-log shipping pipeline for the same logs unless duplicates are intended.

Generate traffic with the [quickstart example](../examples/SetuQuickstart.java):

```text
java @config/jvm.options -cp "setu-server/target/lib/*" examples/SetuQuickstart.java
```

To verify a cross-process client/broker trace, attach the agent to that client too, using a distinct service identity and sampling all client roots for the check:

```text
java @config/jvm.options -javaagent:target/observability/opentelemetry-javaagent-2.33.0.jar -Dotel.javaagent.enabled=true -Dotel.javaagent.configuration-file=config/observability/otel-agent.properties -Dotel.service.name=setu-example -Dotel.resource.attributes=service.instance.id=local-publisher -Dotel.traces.sampler.arg=1.0 -cp "setu-server/target/lib/*" examples/SetuQuickstart.java
```

Quote the `@...`, `-javaagent:...` and `-Dotel...` arguments in PowerShell. The explicit `otel.javaagent.enabled=true` is needed for this source-launchable `.java` example because the agent otherwise disables itself when it detects a JDK tool. In Tempo, each example request should show a `setu-example` client span and its `setu` broker child within the same trace. Applications with an existing active span also see that span above the client attempt. No extra trace identifier is added to message payloads.

Open [Grafana](http://127.0.0.1:3000); the development bundle enables anonymous access by default. Open **Setu / Setu broker overview**. The dashboard includes scrape health, readiness, requests/errors, response record rates, broker p99, CPU cores used, heap/GC, connections, buffer usage, write backlog, append/flush time, member lag and logs. Allow several scrapes before rate panels populate. Short bursts can disappear in a long query window; use sustained traffic for rate diagnosis. Zero-flush intervals have no mean flush duration.

In Explore, select **Setu Loki** and query `{service_name="setu"}`; expand a request log and follow its **TraceID** link to **Setu Tempo**. To see successful request logs, enable DEBUG with `-Dsetu.log.level=DEBUG` and sample all traces while checking locally. In Tempo, search `{ resource.service.name = "setu" }`. The supplied datasources link traces back to Loki using service identity and trace ID. Span links are available only for sampled dispatches with matching logs.

Troubleshooting:

- `/ready` should return 200 and `/metrics` should contain `setu_ready 1` once the listener starts.
- Alloy's local UI at port 12345 shows scrape/remote-write health. `up{job="setu"}=0` means scrape failure; absent series can mean Alloy is stopped or Mimir is unreachable.
- Mimir readiness is available at `http://127.0.0.1:9009/ready`; check Compose logs for backend startup errors.
- Empty Tempo with working metrics usually means the agent is absent, the OTLP endpoint is wrong, or the requests were not sampled. Read agent export errors in the broker console.
- Stop the broker and native Alloy with Ctrl+C, then use `docker compose -f config/observability/compose.yaml down`. Named volumes retain telemetry. Do not delete volumes to troubleshoot a source change.

## Existing LGTM installations

Use the same `/metrics` target with your host Alloy/Prometheus collector and send remote writes to your Mimir endpoint. Add deployment authentication, tenant headers and TLS at the collector; leave the broker operations listener on loopback. The provided collector labels use bounded service and instance identity rather than topic/producer/member labels.

Point the agent's `OTEL_EXPORTER_OTLP_ENDPOINT`, protocol and authentication headers at your existing collector and supply `OTEL_SERVICE_NAME` and `OTEL_RESOURCE_ATTRIBUTES`. Environment variables override the configuration-file defaults; `-Dotel.*` system properties have higher precedence. Deployments own their collector, authentication, retention, resource limits and sampling policy. The code has no dependency on Grafana's SDK or backend URLs.

Collector/agent scraping and export add overhead. Compare with the same observability settings when evaluating performance. This setup prepares telemetry collection; it does not change benchmark qualification or replace external client delivery, latency and timeout measurements. Per-stage benchmark ingestion of these signals remains separate work.
