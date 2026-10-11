package io.setu.benchmark.chaos;

import io.setu.storage.log.StorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the unbounded capacity ramp out of process so a measured JVM OOM cannot kill Surefire. */
class ProgressiveCapacityChaosTests {
    @TempDir Path tempDir;

    @Test
    @Timeout(value = 55, unit = TimeUnit.MINUTES)
    void progressivelyRampsInMemoryProducersAndConsumersUntilTheRuntimeLimit() throws Exception {
        runIsolatedRamp(StorageMode.IN_MEMORY);
    }

    @Test
    @Timeout(value = 55, unit = TimeUnit.MINUTES)
    void progressivelyRampsFileProducersAndConsumersUntilTheRuntimeLimit() throws Exception {
        runIsolatedRamp(StorageMode.FILE);
    }

    private void runIsolatedRamp(StorageMode mode) throws Exception {
        Path logDirectory = Files.createDirectories(Path.of(System.getProperty("SETU_LOG_DIR", "logs"))
                .resolve("capacity").resolve(mode.name().toLowerCase() + "-" + java.util.UUID.randomUUID()));
        Path output = logDirectory.resolve("capacity.log");
        System.out.println("CAPACITY_CAPTURE mode=" + mode + " directory=" + logDirectory.toAbsolutePath());
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>(List.of(executable, "-XX:+UseZGC", "-XX:+UseStringDeduplication"));
        BenchmarkSettings.addJvmArguments(command, 768);
        command.add("-DSETU_LOG_DIR=" + logDirectory.toAbsolutePath());
        for (String property : List.of("setu.benchmark.batch.records", "setu.benchmark.stage.millis", "setu.benchmark.warmup.millis", "setu.benchmark.cpu.hold.millis", "setu.benchmark.max.concurrency", "setu.benchmark.min.stages", "setu.benchmark.memory.log.bytes", "setu.benchmark.jfr", "setu.log.level", "logback.configurationFile")) {
            String value = System.getProperty(property);
            if (value != null) command.add("-D" + property + "=" + value);
        }
        command.addAll(List.of("-cp", classpath, ProgressiveCapacityChaosWorker.class.getName(),
                mode.name(), tempDir.resolve(mode.name().toLowerCase() + "-data").toString()));
        Process child = new ProcessBuilder(command)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            if (!child.waitFor(Duration.ofMinutes(25).toMillis(), TimeUnit.MILLISECONDS)) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
                throw new AssertionError("Capacity ramp exceeded 25 minutes for " + mode + ":\n" + readOutput(output));
            }
            String report = readOutput(output);
            System.out.print(report);
            assertTrue(report.contains("CAPACITY_STAGE mode=" + mode + " stage=1 producers=1 consumers=1"),
                    "Capacity ramp did not complete its first load stage:\n" + report);
            assertTrue(report.contains("CAPACITY_STOP mode=" + mode),
                    "Child did not report a measured capacity boundary (exit " + child.exitValue() + "):\n" + report);
            String stopLine = report.lines()
                    .filter(line -> line.startsWith("CAPACITY_STOP mode=" + mode + " reason="))
                    .findFirst().orElse("");
            assertTrue(stopLine.matches(".*reason=(CPU_SATURATION|JVM_OOM|RESOURCE_LIMIT|BROKER_OR_CLIENT_LIMIT|CLIENT_STALL|CONCURRENCY_COUNTER_LIMIT|CONFIGURED_LIMIT) .*"),
                    "Child stopped without a recognized capacity boundary:\n" + report);
            assertTrue(child.exitValue() == 0 || child.exitValue() == 42,
                    "Capacity worker terminated unexpectedly (exit " + child.exitValue() + "):\n" + report);
            int minimumStages = Integer.getInteger("setu.benchmark.min.stages", 8);
            int maximumConcurrency = Integer.getInteger("setu.benchmark.max.concurrency", 0);
            if (maximumConcurrency == 0 || maximumConcurrency >= (1 << (minimumStages - 1)))
                assertTrue(report.lines().anyMatch(line -> (line.startsWith("CAPACITY_STAGE mode=" + mode + " stage=" + minimumStages + " ")
                                || line.startsWith("CAPACITY_STAGE_TOTAL mode=" + mode + " stage=" + minimumStages + " "))
                                && line.endsWith("stop=ramp")),
                        "Capacity worker stopped before completing the required stage " + minimumStages + ":\n" + report);
            if (Boolean.parseBoolean(System.getProperty("setu.benchmark.jfr", "true"))) {
                Path recording = logDirectory.resolve("capacity.jfr");
                System.out.printf("CAPACITY_JFR_CAPTURE mode=%s path=%s terminal_capture=%s%n", mode,
                        recording.toAbsolutePath(), child.exitValue() == 42 ? "best_effort" : "complete");
                var recordedStages = CapacityJfr.report(recording);
                if (maximumConcurrency == 0 || maximumConcurrency >= (1 << (minimumStages - 1)))
                    for (int stage = 1; stage <= minimumStages; stage++)
                        assertTrue(recordedStages.contains(stage), "JFR is missing completed stage " + stage + " for " + mode);
            }
        } finally {
            if (child.isAlive()) child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static String readOutput(Path output) {
        try { return Files.exists(output) ? Files.readString(output) : "No capacity-worker output."; }
        catch (IOException failure) { return "Could not read capacity-worker output: " + failure; }
    }
}
