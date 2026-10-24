# Running and debugging in IntelliJ IDEA

Open the root `pom.xml` as a Maven project and wait for dependency import. Set **Project Structure → Project → SDK** to JDK 26 and let modules inherit it. The shared configurations in [`.run/`](../.run/) use the project SDK, repository-root working directory, and [shared JVM options](../config/jvm.options). They contain no machine-specific JDK path.

Choose a configuration from the toolbar or **Run → Edit Configurations**. If new configurations have not appeared, use **File → Reload All from Disk**. Application and JUnit configurations build their modules before launch. Use **Run** for ordinary execution or **Debug** for breakpoints; separate copies with fixed debugger ports are unnecessary.

## Broker profiles

The **Setu - Brokers** folder contains four Application configurations. Each launches `io.setu.server.SetuServerMain` from the `setu-server` module and its dependencies. Java changes are compiled before launch without packaging a JAR.

| Configuration | TCP port | HTTP operations port | Durable data |
| --- | ---: | ---: | --- |
| `Broker - FILE blocking (19092)` | 19092 | 18080 | `data/intellij/file-blocking/` |
| `Broker - FILE selector (19093)` | 19093 | 18081 | `data/intellij/file-selector/` |
| `Broker - MEMORY blocking (19094)` | 19094 | 18082 | Volatile |
| `Broker - MEMORY selector (19095)` | 19095 | 18083 | Volatile |

All listeners bind to `127.0.0.1`. These ports leave the standard broker on 9092 available. Separate ports and FILE directories allow the profiles to coexist. The [profile properties](../config/intellij/) override local development settings; unspecified properties retain the [launcher defaults](configuration.md). FILE records remain across restarts; memory records are lost on shutdown.

Open `http://127.0.0.1:18080/ready` or `/metrics` for the FILE blocking profile; substitute the corresponding operations port for another profile. Messaging uses the binary TCP listener.

Each profile sets `io.setu` logging to DEBUG and writes to `logs/intellij/<profile>/setu.log`. Change `-Dsetu.log.level=DEBUG` to `INFO` in VM options for quieter logs. `config/jvm.options` supplies ZGC and the required Chronicle module/native access.

Local profiles disable idle socket timeout and extend setup, frame, write, acknowledgement and consumer inactivity deadlines to ten minutes. This permits longer breakpoint pauses and changes expiry timing relative to the normal defaults. Client examples still have 35-second read timeouts, and long polls remain capped at 30 seconds. Resume before a client times out, or adjust its read timeout when investigating longer pauses. Prefer **Suspend: Thread** for breakpoints when other connections need to progress. Use normal properties and tests when investigating production timeout behavior.

To start, select **Broker - FILE blocking (19092)**, press **Debug**, and wait for `Setu listening on`. Useful breakpoint locations include `SetuServerMain`, `BrokerRequestHandler`, `DefaultBroker.execute`, `ProtocolCodec`, `ChronicleMessageLog`, and `InMemoryMessageLog`. The selector profiles exercise `SelectorTcpServer`. Stop each broker using its own console; preserve its data for recovery debugging.

## Examples

The **Setu - Examples** folder runs the checked-in [Java examples](../examples/README.md) through JDK 26's source launcher with the current `setu-server` module classpath. Generated example JARs are unnecessary. The Application main class is `com.sun.tools.javac.launcher.SourceLauncher`; program arguments identify the example source and its arguments. Use Debug to step into broker/client module code.

| Configuration | Broker needed |
| --- | --- |
| `Example - Quickstart - FILE blocking (19092)` | FILE blocking profile |
| `Example - Quickstart - FILE selector (19093)` | FILE selector profile |
| `Example - Quickstart - MEMORY blocking (19094)` | MEMORY blocking profile |
| `Example - Quickstart - MEMORY selector (19095)` | MEMORY selector profile |
| `Example - Batch and partitions` | Port 19092 by default |
| `Example - Long polling` | Port 19092 by default |
| `Example - Embedded memory broker` | Starts and closes its own broker |
| `Example - File recovery` | Fresh `examples/target/recovery-*` directory |
| `Example - File retention (isolated demo)` | Fresh isolated partition; no external directory argument |
| `Example - Memory retention` | Starts and closes its own broker |

Start a broker first, then run its matching client configuration while it stays running. Quickstart prints two records and exits after acknowledgement and leave. Repeated FILE runs advance offsets. To target another profile from batch or long polling, change the trailing `host port` arguments. Example logs go to `logs/intellij/examples/`. File demos preserve generated data for inspection.

Earlier local configurations named `Setu Broker (local)` and `Setu Quickstart (produce and consume)`, if present under ignored `.idea/`, launch previously packaged JARs on 9092. Prefer the shared source-based profiles for development.

## Tests and builds

The **Setu - Debug tests** folder provides JUnit configurations for `storage`, `core`, `security`, `server`, and `integration`, plus focused `tls`, `transactions`, and `async-client` integration classes. Run or Debug these for IntelliJ's test tree and breakpoints. Security and server suites include the Mockito startup agent pinned by the root POM; update their agent paths if that version changes. `$MAVEN_REPOSITORY$` resolves to IntelliJ's Maven repository. Client behavior is covered by integration tests; `setu-client` has no standalone test suite.

Tests create their own brokers and ephemeral ports with ordinary test deadlines. mTLS tests generate temporary credentials using JDK `keytool`; standalone profiles serve plaintext. PostgreSQL tests are skipped unless `SETU_TEST_POSTGRES_URL` is set. Configure that variable and `SETU_TEST_POSTGRES_USER` / `SETU_TEST_POSTGRES_PASSWORD` privately; do not save credentials in shared files. See the [integration guide](../setu-integration-tests/README.md) and [PostgreSQL guide](postgres-outbox.md).

The **Setu - Build and verification** folder uses the pinned **Maven Wrapper** and **Project JDK**:

| Configuration | Maven command after the wrapper |
| --- | --- |
| `Build - Compile broker and dependencies` | `-pl setu-server -am compile` |
| `Build - Package broker (skip tests)` | `-pl setu-server -am -DskipTests package` |
| `Verify - Production and TCP-mTLS tests` | `-pl setu-integration-tests -am test` |
| `Verify - Benchmark helpers (no load or chaos)` | `-pl setu-benchmark -am -Dtest=*Tests,!*ChaosTests,!*BenchmarkTests -Dsurefire.failIfNoSpecifiedTests=false test` |

Packaging explicitly skips tests; use functional verification for the production test reactor. The benchmark helper filter excludes load and chaos entry points. No configuration invokes unfiltered root `test` or Linux-only benchmark profiles. Use JUnit configurations to debug tests: Maven Debug can target Maven itself instead of its forked Surefire JVM. Reports remain in module `target/surefire-reports/` directories.

For CLI equivalents, set `JAVA_HOME` and `PATH` to JDK 26 and use `./mvnw.cmd` on Windows or `bash ./mvnw` on Linux/macOS. IntelliJ configurations do not change your system environment.

## Attach to a separately launched broker

**Setu - Debug tools → Attach - Local JVM (5005)** connects to a loopback JDWP listener. Build the package, then launch from the repository root:

```powershell
java '@config/jvm.options' '-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005' -jar setu-server/target/setu-server-1.0.0-SNAPSHOT.jar --config config/intellij/file-blocking.properties
```

The JVM waits for the debugger before starting; `suspend=n` starts immediately. JDWP port 5005 is separate from messaging port 19092 and operations port 18080. Start only one owner of a FILE directory. For ordinary source debugging, use the broker Application configurations directly.
