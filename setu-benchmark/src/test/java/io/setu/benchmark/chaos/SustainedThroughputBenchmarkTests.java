package io.setu.benchmark.chaos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SustainedThroughputBenchmarkTests {
    @TempDir Path temporary;
    @Test @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void memory() throws Exception { run("IN_MEMORY"); }
    @Test @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void file() throws Exception { run("FILE"); }

    private void run(String mode) throws Exception {
        Path capture = Files.createDirectories(Path.of(System.getProperty("SETU_LOG_DIR", "logs"))
                .resolve("sustained").resolve(mode + "-" + UUID.randomUUID()));
        Path log = capture.resolve("worker.log");
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<>(List.of(java));
        BenchmarkSettings.addJvmArguments(command, 768);
        for (String key : List.of("setu.benchmark.measurement", "setu.benchmark.stage.millis",
                "setu.benchmark.warmup.millis", "setu.benchmark.max.concurrency", "setu.benchmark.batch.records", "setu.benchmark.jfr")) {
            if (System.getProperty(key) != null) command.add("-D" + key + "=" + System.getProperty(key));
        }
        command.addAll(List.of("-Dsetu.log.level=INFO", "-DSETU_LOG_DIR=" + capture.toAbsolutePath(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                SustainedThroughputWorker.class.getName(), mode, temporary.resolve(mode).toString()));
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(25, TimeUnit.MINUTES), "sustained worker timed out: " + log);
            String report = Files.readString(log);
            System.out.println("SUSTAINED_CAPTURE mode=" + mode + " path=" + log.toAbsolutePath());
            System.out.print(report);
            assertEquals(0, child.exitValue(), report);
            assertTrue(report.contains("SUSTAINED_STAGE mode=" + mode + " kind=ramp concurrency=1"), report);
            assertTrue(report.contains("SUSTAINED_END mode=" + mode), report);
            if (Boolean.getBoolean("setu.benchmark.jfr")) CapacityJfr.report(capture.resolve("sustained.jfr"));
        } finally {
            if (child.isAlive()) child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }
}
