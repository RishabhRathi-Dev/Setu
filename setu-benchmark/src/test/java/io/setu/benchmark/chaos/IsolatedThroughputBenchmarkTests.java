package io.setu.benchmark.chaos;

import io.setu.storage.log.StorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Sustainable fixed-rate checks are deliberately separate from ProgressiveCapacityChaosTests exhaustion. */
class IsolatedThroughputBenchmarkTests {
    @TempDir Path temporary;
    @Test @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void measuresSeparateBrokerAndClientProcessesWithOneAndFourPartitions() throws Exception {
        int rate = Integer.getInteger("setu.benchmark.steady.rate", 10_000);
        int seconds = Integer.getInteger("setu.benchmark.steady.seconds", 3);
        if (rate < 128 || rate > 1_000_000 || seconds < 1 || seconds > 30)
            throw new IllegalArgumentException("steady rate must be 128..1000000 and seconds 1..30");
        List<Path> missed = new ArrayList<>();
        for (StorageMode mode : StorageMode.values()) for (int partitions : new int[]{1, 4}) {
            Path failure = run(mode, partitions, rate, seconds);
            if (failure != null) missed.add(failure);
        }
        if (Boolean.parseBoolean(System.getProperty("setu.benchmark.steady.enforce", "false")))
            assertTrue(missed.isEmpty(), "Steady offered load missed; retained captures: " + missed);
    }
    private Path run(StorageMode mode, int partitions, int rate, int seconds) throws Exception {
        Path capture = Files.createDirectories(Path.of(System.getProperty("SETU_LOG_DIR", "logs"))
                .resolve("steady").resolve(mode + "-" + partitions + "-" + UUID.randomUUID()));
        Path brokerLog = capture.resolve("broker.log"), clientLog = capture.resolve("client.log");
        System.out.printf("STEADY_CAPTURE mode=%s partitions=%d directory=%s%n", mode, partitions, capture.toAbsolutePath());
        Process broker = child(SteadyBrokerWorker.class, brokerLog, mode.name(), capture.toAbsolutePath().toString(),
                temporary.resolve(mode + "-" + partitions).toAbsolutePath().toString());
        Process clients = null;
        try {
            await(capture.resolve("ready"), broker, 20);
            clients = child(SteadyLoadWorker.class, clientLog, Files.readString(capture.resolve("ready")),
                    Integer.toString(partitions), Integer.toString(rate), Integer.toString(seconds), capture.toAbsolutePath().toString());
            assertTrue(clients.waitFor(seconds + 25L, TimeUnit.SECONDS), "client process timed out");
            String report = Files.readString(clientLog);
            assertEquals(0, clients.exitValue(), report);
            var measurement = SteadyMeasurement.parse(report, partitions);
            await(capture.resolve("measure.done"), broker, 10);
            Files.writeString(capture.resolve("shutdown"), "shutdown");
            assertTrue(broker.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, broker.exitValue(), Files.readString(brokerLog));
            if (measurement.missedRecords() > 0) {
                String prefix = System.getenv("GITHUB_ACTIONS") == null ? "WARNING: " : "::warning::";
                System.out.printf("%sSteady offered load missed mode=%s partitions=%d missed_records=%d capture=%s%n",
                        prefix, mode, partitions, measurement.missedRecords(), capture.toAbsolutePath());
                return capture;
            }
            return null;
        } finally {
            if (clients != null && clients.isAlive()) clients.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            if (broker.isAlive()) broker.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            if (Files.exists(clientLog)) System.out.print(Files.readString(clientLog));
            if (Files.exists(brokerLog)) System.out.print(Files.readString(brokerLog));
        }
    }
    private static Process child(Class<?> worker, Path output, String... arguments) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<>(List.of(java));
        BenchmarkSettings.addJvmArguments(command, 512);
        command.addAll(List.of("-Dsetu.log.level=WARN",
                "-DSETU_LOG_DIR=" + output.getParent().resolve(output.getFileName().toString().replace(".log", "-operational")).toAbsolutePath(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), worker.getName()));
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }
    private static void await(Path signal, Process child, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!Files.exists(signal) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(Files.exists(signal), "worker failed or timed out waiting for " + signal);
    }
}
