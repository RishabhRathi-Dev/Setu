package io.setu.benchmark.chaos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CapacityOomReportingChaosTests {
    @TempDir Path tempDir;

    @Test
    void reportsRealHeapExhaustionInCaughtAndUncaughtPathsForBothStorageModes() throws Exception {
        for (String mode : new String[]{"FILE", "IN_MEMORY"}) {
            for (String path : new String[]{"caught", "uncaught"}) {
                Result result = runWorker(mode, path);
                assertEquals(42, result.exit, result.output);
                assertEquals(1, result.output.lines().filter(line -> line.startsWith("CAPACITY_STOP")).count(), result.output);
                assertTrue(result.output.contains("CAPACITY_STOP mode=" + mode
                        + " reason=JVM_OOM stage=3 producers=4 consumers=4"), result.output);
            }
        }
    }

    @Test
    void doesNotReportAnUnexpectedCrashAsHeapExhaustion() throws Exception {
        Result result = runWorker("FILE", "unexpected");
        assertEquals(1, result.exit, result.output);
        assertTrue(result.output.contains("IllegalStateException: Unexpected fixture failure"), result.output);
        assertFalse(result.output.contains("CAPACITY_STOP"), result.output);
    }

    private Result runWorker(String mode, String path) throws Exception {
        Path output = Files.createDirectories(tempDir.resolve("logs")).resolve(mode + "-" + path + ".log");
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        Process child = new ProcessBuilder(BenchmarkRuntime.childCommand(executable, "-Xms16m", "-Xmx32m", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                CapacityOomReportingWorker.class.getName(), mode, path))
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "OOM fixture did not terminate");
            return new Result(child.exitValue(), Files.readString(output));
        } finally {
            if (child.isAlive()) child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private record Result(int exit, String output) { }
}
