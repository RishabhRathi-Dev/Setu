# Configuration reference

The standalone entry point is `io.setu.server.SetuServerMain`. Run from the repository root:

```powershell
.\scripts\start-broker.ps1 --config config/server.properties
```

On Linux/macOS use `bash ./scripts/start-broker.sh --config config/server.properties`. With no arguments, the launcher uses the same built-in defaults. Properties use Java's `.properties` format. Unknown keys, invalid numbers, and invalid enum/transport values fail startup. Relative paths resolve against the process working directory; there is no live reload.

The source of defaults and accepted keys is [SetuServerMain.java](../setu-server/src/main/java/io/setu/server/SetuServerMain.java). The [checked-in properties](../config/server.properties) provide an editable local baseline. Byte values use bytes, not suffixes such as `128m`; all `millis` values use milliseconds.

## Network and transport

The optional operations listener uses `operations.port=-1` (disabled), or `0..65535` to bind on 127.0.0.1. Zero selects an ephemeral port. It serves read-only `/metrics` and `/ready`; see [observability](observability.md). It does not change the broker plaintext/TLS boundary.

The [LGTM walkthrough](observability.md#local-lgtm-stack) supplies a dedicated development properties file, host-local Alloy scraping and optional OpenTelemetry Java agent configuration. OTLP export and sampling use the agent's `otel.*` settings, not broker properties. The standard launcher keeps export disabled unless an SDK/agent is installed. Logs include `trace_id` and `span_id` when the agent supplies trace context.

The [container profile](containers.md) uses existing broker properties from `docker/server.properties`: TCP binds to container interfaces, FILE data goes to `/var/lib/setu`, and operations stays on loopback port 9093. Compose supplies process resource limits and JVM/agent environment settings without adding broker property names.

| Property | Default | Meaning / validation |
| --- | --- | --- |
| `bind.host` | `127.0.0.1` | Local bind address |
| `bind.port` | `9092` | Port; `0` selects an available port reported in the startup log |
| `transport` | `blocking` | Exact lowercase `blocking` or `selector`; selector is plaintext only |
| `connections.max` | `10000` | Positive maximum tracked connections; excess connections close |
| `accept.backlog` | `256` | Positive socket accept backlog |
| `socket.timeout.millis` | `60000` | Non-negative read timeout for blocking sockets, idle timeout for selector; zero disables |
| `handshake.timeout.millis` | `10000` | Positive absolute connection-session setup deadline, including embedded TLS handshake/authentication |
| `frame.timeout.millis` | `30000` | Positive deadline from the first header byte through the complete body, on both transports; trickling does not extend it |
| `write.timeout.millis` | `30000` | Positive total reply-write deadline; slow readers disconnect |

The properties launcher is plaintext. TLS/mTLS requires [secure embedding](../setu-server/README.md#secure-broker-wiring-example). With embedded blocking TLS, the socket read timeout also applies to handshake reads. The absolute setup and frame deadlines still apply when the socket timeout is zero. Frame deadlines end before broker dispatch, so they do not limit long polls. An idle connection awaiting its first frame byte is governed by the socket timeout. Socket timeout values and connection counts are parsed as Java integers; ports must satisfy the socket API's range of 0–65,535.

## Storage and retention

FILE automatically selects Setu's Java FFM io_uring implementation on supported
Linux. Other platforms retain the existing portable backend. Unsupported or
policy-denied Linux ring setup logs a warning and falls back at startup;
resource, write and durability failures remain errors. No backend-selector,
native-library path or ring-tuning property is required. Keep the shared JVM
native-access options. See [FILE I/O ownership and limits](../setu-storage/README.md#file-io).

| Property | Default | Meaning / validation |
| --- | --- | --- |
| `storage.mode` | `FILE` | Exact uppercase `FILE` or `IN_MEMORY` |
| `data.dir` | `data` | File-mode data root; unused by memory mode |
| `record.max.bytes` | `1048576` (1 MiB) | Maximum record payload; 0–15 MiB, also subject to frame/protocol limits |
| `queue.block.bytes` | `67108864` (64 MiB) | Positive Chronicle mapping block size; effective minimum 64 MiB |
| `memory.log.max.bytes` | `268435456` (256 MiB) | Non-negative shared accounted memory-log budget; zero disables the budget |
| `memory.retention.millis` | `0` | Lossy maximum age in memory mode; 0–365 days; zero disables timed expiry |
| `file.retention.millis` | `0` | File-mode age limit from the end of each daily cycle; 0–365 days; zero disables expiry |

Chronicle rolls files daily. Mapping block size does not cap daily file growth. Positive `file.retention.millis` enables cleanup at startup and every minute, independently of consumer progress. Only whole completed cycles expire; the newest populated file is always retained, even while idle. Record age can therefore exceed the configured duration by up to one daily cycle plus the sweep delay, and the newest file remains until a later cycle receives data. Fetches before the retained boundary return `OFFSET_OUT_OF_RANGE` with a resume offset. For seven days, set `file.retention.millis=604800000`. Monitor free disk for durable workloads. File write admission is fixed at 32 MiB / 1,024 outstanding requests per partition; it is not a standalone property.

Memory reclamation normally follows registered members' progress. Members start at the partition end on join; no members allows immediate reclamation. Positive timed retention can discard records a consumer has not processed. These memory-only settings do not configure file retention or a JVM/process memory limit.

### File retention examples

To keep completed daily files for seven days, edit `config/server.properties`:

```properties
storage.mode=FILE
data.dir=data
file.retention.millis=604800000
```

Restart the broker with the properties file; the setting applies to every existing and newly created file partition. Startup cleanup can immediately expire older files. Configuration is read only at startup.

```powershell
.\scripts\start-broker.ps1 --config config/server.properties
```

On Linux/macOS, use `bash ./scripts/start-broker.sh --config config/server.properties`.

| Policy | `file.retention.millis` |
| --- | --- |
| Keep all files (default) | `0` |
| One day after cycle end | `86400000` |
| Seven days after cycle end | `604800000` |
| Thirty days after cycle end | `2592000000` |

Changing the value back to `0` stops future automatic expiry after restart. It does not restore deleted records or reset the retained offset. `memory.retention.millis` does not affect file partitions, and `file.retention.millis` does not affect memory partitions. See the [worked expiry timeline](operations.md#file-retention-timeline) and [embedded broker example](../setu-core/README.md#file-retention-example).

## Buffers and consumer deadlines

| Property | Default | Meaning / validation |
| --- | --- | --- |
| `requests.max.bytes` | `134217728` (128 MiB) | Positive shared request allocation credit, including connection buffering |
| `responses.max.bytes` | `134217728` (128 MiB) | Positive shared response/materialization credit, held through writes |
| `consumers.max` | `10000` | Positive maximum active group/member identities; separately bounds retained FILE managed groups |
| `consumer.partitions.max` | `100000` | Positive maximum sum of joined topic partition counts; separately bounds retained FILE managed-group partition offsets |
| `ack.timeout.millis` | `30000` | Positive deadline for member acknowledgement of fetched records |
| `consumer.timeout.millis` | `60000` | Positive member inactivity deadline |

New joins reserve both metadata limits atomically before allocating progress entries. Existing members can refresh at capacity. Leave and expiry release active membership capacity; rejected joins return `QUOTA_EXCEEDED`. Group/member identities are capped at 4 KiB UTF-8 each. A commit without a member validates the offset but stores no state. `FETCH_OFFSET` returns a registered member's acknowledged next offset; managed members can also query `GROUP_ASSIGNMENT`. Explicit member progress lasts until leave or expiry, while managed group offsets remain after departure. FILE brokers recover memberships and acknowledged progress on restart; IN_MEMORY metadata is volatile. See [consumer semantics](../setu-core/README.md#broker-semantics).

Consumer maintenance runs every 10 ms. An expired member must rejoin. Heartbeats maintain liveness but do not acknowledge processed records. Long-poll wait is a request parameter, capped at 30,000 ms; configure the client's read timeout above the requested wait.

When budgets are exhausted, the server returns `QUOTA_EXCEEDED` if it can admit an error response, otherwise it closes the connection. Budget headroom must allow protocol metadata and accounting, as well as payload bytes. See [admission and failure behavior](../setu-server/README.md#admission-and-failure-behavior).

## JVM, logging, and AOT

The [IntelliJ development profiles](intellij.md) provide isolated loopback ports, data directories, metrics, and longer breakpoint deadlines using existing properties. The standard launcher defaults remain unchanged.

| Control | Location | Behavior |
| --- | --- | --- |
| JDK selection | `JAVA_HOME`, or `java` on `PATH` | Launchers require JDK 26; Maven also uses `JAVA_HOME` |
| JVM options | [config/jvm.options](../config/jvm.options) | ZGC, string deduplication, required Chronicle access, analytics disabled |
| Heap/direct memory | JVM arguments in embedding/direct launches | Use workload-appropriate headroom; no standalone heap property |
| Log directory | `SETU_LOG_DIR` | Default `logs`; contains `setu.log` and rolling archives |
| Application log level | `-Dsetu.log.level` or `SETU_LOG_LEVEL` | System property takes precedence; `io.setu` defaults to `INFO` (DEBUG is opt-in), root to `INFO` |
| AOT generation | Launcher `--train-aot` | Creates `setu-server/target/setu-server.aot` using training traffic |
| AOT bypass | Launcher `--no-aot` before broker arguments | Skips an existing strict-mode cache |

The runtime uses [Logback configuration](../config/logback.xml) bundled in the server JAR. Editing that source requires rebuilding the JAR, or supplying an explicit Logback configuration to a direct JVM launch. Archives roll daily or at 100 MB, retain 14 days, and have a 2 GB total cap.

For a direct launch with an explicit heap and log level:

```powershell
java '@config/jvm.options' -Xms256m -Xmx1g -Dsetu.log.level=INFO -jar setu-server/target/setu-server-1.0.0-SNAPSHOT.jar --config config/server.properties
```

Rebuild AOT caches when JDK, platform, application, or dependency JARs change. [Operations](operations.md) covers launch artifacts and recovery; the [server guide](../setu-server/README.md) covers custom embedded listeners and TLS policy.

## Metadata journal

`journal.compact.bytes` defaults to `67108864` (64 MiB); nonnegative integer bytes are required. Zero disables automatic compaction. FILE maintenance checks appended journal payload bytes once per second and takes a metadata checkpoint after reaching the trigger. This is not a hard disk quota: mapped-file overhead, checkpoint size and the current workload affect physical usage. IN_MEMORY has no journal. See [checkpoint guarantees, pauses and upgrade compatibility](journal-checkpoints.md).
