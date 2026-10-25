# Run Setu in containers

The source checkout includes a [Dockerfile](../Dockerfile), a [broker Compose file](../compose.yaml), a [published-image override](../compose.image.yaml), and an [LGTM override](../compose.lgtm.yaml). Use a Linux container engine with Docker Compose **2.20.0 or later**; the LGTM override uses Compose `include` to reuse the existing observability services. Docker Desktop must use Linux containers on Windows. A host JDK or Maven installation is not required to build or run the image.

## Broker

From the repository root, the same command works in PowerShell and Bash:

```text
docker compose up --build -d
docker compose ps
docker compose logs -f setu
```

The image builds the server and its reactor dependencies with the Maven Wrapper and JDK 26, then copies the server JAR beside its `lib/` dependencies into a separate runtime stage. Build dependencies use a BuildKit cache; local `target/`, data, credentials and Git state are excluded from the build context by an allowlist. Docker build skips tests; the [container workflow](../.github/workflows/container.yml) runs functional tests before building and exercises the image before publication.

The runtime uses Eclipse Temurin **26.0.2.1+1** on Ubuntu Noble, runs as UID/GID **10001**, loads all shared `config/jvm.options`, and starts Java directly as PID 1. The JDK remains available for diagnostics. There is no AOT cache in the image. Build an image separately with `docker build -t setu:local .`; `JAVA_IMAGE` is a build argument for another compatible JDK 26 image.

The default [container properties](../docker/server.properties) select FILE storage, broker port 9092 on all container interfaces, and operations port 9093 on container loopback. Compose publishes only plaintext **127.0.0.1:9092** on the host. TLS/mTLS remains an embedding API feature; the properties launcher does not configure it. To accept remote clients, deliberately change the published host address in your deployment configuration.

The defaults follow the fixed FILE [CI baseline](../scripts/run-benchmarks.py), rounding its fractional CPU allocation up:

| Limit | CI FILE baseline | Docker default |
| --- | --- | --- |
| CPU quota | 1.6 CPUs | 2 CPUs |
| Container memory | 5 GiB | 5 GiB |
| Swap | Disabled | Disabled |
| Initial / maximum heap | 64 / 128 MiB | 64 / 128 MiB |
| Direct memory | 128 MiB | 128 MiB |
| JVM active processors | 2 | 2 |
| Retained memory log | 64 MiB | 64 MiB (unused in FILE mode) |

Compose enforces CPU/memory/swap limits; the image supplies the JVM defaults even when run directly. CI's envelope includes its workload processes, while Compose limits the broker container; matching settings does not establish equivalent throughput or benchmark qualification. `JDK_JAVA_OPTIONS` configures the JVM limits; edit the Compose service's `cpus`, `mem_limit`, `memswap_limit` and JVM options together when tuning. Shared Chronicle/native-access options remain loaded from the image's JVM options file. If selecting IN_MEMORY, use its CI heap default of 256 MiB and retain the 64 MiB log budget.

Compose mounts persistent named volumes at `/var/lib/setu` and `/var/log/setu`, uses a read-only root filesystem and a bounded writable `/tmp`, and drops Linux capabilities. The image initializes the data/log directories for its runtime user; bind mounts must be writable by UID/GID 10001. Logback rotates and caps its files. Compose also caps the Docker console log at three 10 MiB files. `docker compose logs` shows console output.

The image health check runs a small compiled Java probe against `http://127.0.0.1:9093/ready`. It requires HTTP 200 and the exact readiness body, with bounded connection/read timeouts; redirects fail. It does not attach the telemetry agent to the probe JVM. `docker compose ps` shows health. A failed health check alone does not trigger Compose's restart policy; process exits do. Stop sends SIGTERM directly to the broker, with a 60-second grace period for draining accepted storage work. Increase that period for deployments with longer shutdown requirements.

To generate traffic without installing Java on the host, copy and run the existing example inside the broker container:

```text
docker compose cp examples/SetuQuickstart.java setu:/tmp/SetuQuickstart.java
docker compose exec setu java '@/opt/setu/config/jvm.options' -cp '/opt/setu/lib/*' /tmp/SetuQuickstart.java 127.0.0.1 9092
```

The example checks broker error codes, produces two records and acknowledges them. It can be rerun; offsets increase in FILE storage. Single quotes in these commands work in PowerShell and Bash.

## Published image

The [Container image workflow](../.github/workflows/container.yml) builds and tests on pushes and pull requests. Successful default-branch runs publish **linux/amd64** images to `ghcr.io/rishabhrathi-dev/setu`. `latest` points to the most recently published default-branch image; `sha-<full-commit-id>` identifies the tested revision. Use an image digest when a deployment must pin exact contents. The workflow publishes the same image archive that passed its container smoke test, without rebuilding it in the publishing job. Pull requests and other branches retain the tested image archive without publishing. Manual runs are also available through **Actions → Container image → Run workflow**, with publication limited to the default branch.

To pull the image instead of building locally:

```text
docker compose -f compose.yaml -f compose.image.yaml up --no-build -d
docker compose -f compose.yaml -f compose.image.yaml ps
```

Set `SETU_IMAGE` to a revision tag or digest before running Compose to select another published image. The first publication requires a successful workflow run on the default branch and permission for its built-in `GITHUB_TOKEN` to write packages. Repository maintainers must set the package visibility to **public** for anonymous pulls; GitHub initially publishes packages as private. See [GitHub's registry documentation](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry). An existing package must grant this repository Actions access.

Every successful verification job also retains `setu-container-image` for 14 days, containing `setu-image.tar.gz` and its SHA-256 checksum. Download the artifact through GitHub Actions, verify the checksum, then run `docker load --input setu-image.tar.gz`. The archive carries tag `setu:ci`; set `SETU_IMAGE=setu:ci` in your shell and use `docker compose up --no-build -d` to run it with the base file. The published-image override always pulls from the registry, so omit that override when loading a local archive.

## Broker with LGTM

Use the override file with the base file on every command for the full stack:

```text
docker compose -f compose.yaml -f compose.lgtm.yaml up --build -d
docker compose -f compose.yaml -f compose.lgtm.yaml ps
docker compose -f compose.yaml -f compose.lgtm.yaml logs -f setu alloy
```

For a published broker image, add `-f compose.image.yaml` before `-f compose.lgtm.yaml` and use `up --no-build -d`. Keep the same file list on subsequent commands.

This starts the broker, Grafana/Loki/Tempo development bundle, Mimir and Alloy. The backend services reuse [the existing configuration](../config/observability/compose.yaml). Grafana is at [127.0.0.1:3000](http://127.0.0.1:3000) with development anonymous access. Open **Setu / Setu broker overview**. The development bundle also contains Prometheus, but the Setu dashboard uses the **Setu Mimir** datasource.

The image contains the pinned OpenTelemetry Java agent **2.33.0**. The LGTM override attaches it through `JAVA_TOOL_OPTIONS` and sends OTLP logs/traces to `http://lgtm:4318`. Service name is `setu`, instance is `local-setu`, and the supplied parent-based sampler samples 10% of root traces. Agent metrics export stays disabled; JVM/broker metrics use the existing Prometheus API. The standalone broker service does not start an exporter unless an agent/SDK is attached; protocol trace propagation is mandatory in either setup.

Alloy **1.21.2** shares the broker's network namespace with `network_mode: service:setu`, scraping **127.0.0.1:9093** and remote-writing to **http://mimir:9009/api/v1/push**. It waits for broker health before starting. This keeps the operations listener private and works without host networking or a native Alloy installation. Alloy's queue data uses its own persistent volume. The observability UI/exporter ports remain bound to host loopback by the included development Compose file.

For traffic, use the same `cp`/`exec` commands above with `-f compose.yaml -f compose.lgtm.yaml`. See [observability](observability.md) for query meanings, sampling, request trace propagation and correlation. The separately documented host-broker/native-Alloy setup remains available; do not run it alongside this stack on the same host ports. This Compose stack is a local development setup, not a production LGTM installation.

## Configuration and lifecycle

To customize broker properties, mount a complete properties file read-only at `/etc/setu/server.properties`. Preserve `bind.host=0.0.0.0` for container access, `data.dir=/var/lib/setu` for the named data volume, and `operations.port=9093` for the default health/scrape targets. If you change the operations port, update `SETU_HEALTH_URL` and Alloy's scrape target together. The image also accepts command arguments after its Java entrypoint, for example `--config /path/in/container.properties`.

Override `OTEL_EXPORTER_OTLP_ENDPOINT`, resource identity, sampling or export headers in your deployment environment to use an existing collector. The agent configuration is at `/opt/setu/config/observability/otel-agent.properties`. Keep authentication material out of images and tracked properties. Additional JVM arguments belong in `JDK_JAVA_OPTIONS`; the LGTM override uses `JAVA_TOOL_OPTIONS` for the agent. Java's command-line health-probe limits override the service's heap options for that short-lived JVM.

Stop and remove containers with the matching Compose invocation:

```text
docker compose down
docker compose -f compose.yaml -f compose.lgtm.yaml down
```

Use the command matching the stack you started. Named volumes retain broker data, logs and telemetry. Do not use volume deletion for routine rebuilds or source changes. See [journal compatibility](journal-checkpoints.md) and [storage guarantees](../setu-storage/README.md).

## Verification

Validate configuration without starting services:

```text
docker compose config --quiet
docker compose -f compose.yaml -f compose.image.yaml config --quiet
docker compose -f compose.yaml -f compose.lgtm.yaml config --quiet
```

The repository's Python suite compiles and tests the actual Java readiness probe against healthy, unhealthy, redirected, malformed and unreachable HTTP endpoints. It needs JDK 26 for those probe tests. The regular Java integration suite covers broker behavior; the recovery demo and distribution checks cover durable recovery and packaging.

With Docker Engine available, run `python scripts/verify-container.py --image setu:local` after building. It checks readiness, an accessible published TCP port, the unprivileged user, a read-only root filesystem and the CI resource limits. The packaged Java client produces, fetches and acknowledges records; after graceful broker restart it verifies the recovered payloads and offsets. Each run retains fresh evidence under `target/container-smoke/`, preserving previous results. Only the test's uniquely named container and data volume are removed. This smoke test exercises the broker image; full LGTM backend startup is a separate operational check.
