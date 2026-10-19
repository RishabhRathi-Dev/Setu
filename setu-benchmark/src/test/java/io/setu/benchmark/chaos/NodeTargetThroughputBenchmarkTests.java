package io.setu.benchmark.chaos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: three independent 30-second windows per target load and storage mode. */
@EnabledIfSystemProperty(named = "setu.benchmark.node.targets", matches = "true")
class NodeTargetThroughputBenchmarkTests {
    @TempDir Path temporary;
    @Test @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void memory() throws Exception { run("IN_MEMORY"); }
    @Test @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void file() throws Exception { run("FILE"); }

    private void run(String mode) throws Exception {
        List<String> failed = new ArrayList<>();
        for (int repeat = 1; repeat <= 3; repeat++) {
            Path capture = Files.createDirectories(Path.of(System.getProperty("SETU_LOG_DIR", "logs"))
                    .resolve(System.getProperty("setu.benchmark.target.profile", "reference").equals("ci-baseline")
                            ? "ci-baseline" : "node-target").resolve(mode + "-" + repeat + "-" + UUID.randomUUID()));
            Path log = capture.resolve("worker.log");
            String java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            List<String> command = new ArrayList<>(List.of(java));
            BenchmarkSettings.addJvmArguments(command, mode.equals("IN_MEMORY") ? 256 : 128);
            command.addAll(List.of("-Dsetu.benchmark.measurement=full", "-Dsetu.benchmark.stage.millis=30000",
                    "-Dsetu.benchmark.warmup.millis=10000", "-Dsetu.benchmark.jfr=" + System.getProperty("setu.benchmark.jfr", "true"),
                    "-Dsetu.log.level=ERROR", "-DSETU_LOG_DIR=" + capture.toAbsolutePath(),
                    "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    NodeTargetThroughputWorker.class.getName(), mode, temporary.resolve(mode + "-" + repeat).toString()));
            Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(child.waitFor(8, TimeUnit.MINUTES), "node target worker timed out: " + log);
                String report = Files.readString(log);
                System.out.printf("NODE_TARGET_CAPTURE mode=%s repeat=%d path=%s%n", mode, repeat, log.toAbsolutePath());
                System.out.print(report);
                assertEquals(0, child.exitValue(), report);
                if (!report.contains("NODE_TARGET_END mode=" + mode + " qualified=true")) failed.add(log.toString());
                if (Boolean.parseBoolean(System.getProperty("setu.benchmark.jfr", "true")))
                    assertEquals(Set.of(1, 2, 3), CapacityJfr.report(capture.resolve("node-target.jfr")));
            } finally {
                if (child.isAlive()) child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        }
        if (Boolean.parseBoolean(System.getProperty("setu.benchmark.node.targets.enforce", "false")))
            assertTrue(failed.isEmpty(), "Target criteria failed; retained captures: " + failed);
        else if (!failed.isEmpty())
            System.out.println("WARNING: Performance expectations unmet; retained captures: " + failed);
    }
}
