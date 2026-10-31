package io.setu.benchmark.chaos;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Unmeasured coordinator support: every load stage owns its JVM, logs and JFR recording. */
final class BenchmarkStageProcess {
    private BenchmarkStageProcess() { }

    static Result run(Class<?> worker, int stage, String... arguments) throws Exception {
        Path capture = Path.of(System.getProperty("SETU_LOG_DIR", "logs")).toAbsolutePath();
        Path directory = Files.createDirectories(capture.resolve("stages").resolve(String.format("%04d", stage)));
        Path recordings = Files.createDirectories(capture.resolve("recordings"));
        Path log = directory.resolve("worker.log");
        List<String> command = new ArrayList<>();
        command.add(executable("java"));
        // Preserve the exact worker VM budget and Chronicle/module flags, including @file expansion.
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        for (String property : System.getProperties().stringPropertyNames()) {
            if (property.startsWith("setu.benchmark.") || property.equals("setu.log.level")
                    || property.equals("logback.configurationFile") || property.equals("java.io.tmpdir"))
                command.add("-D" + property + "=" + System.getProperty(property));
        }
        command.addAll(List.of("-DSETU_LOG_DIR=" + directory, "-Dsetu.benchmark.stage.id=" + stage,
                "-Dsetu.benchmark.stage.recording=" + recordings.resolve(String.format("%04d.jfr", stage)),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), worker.getName()));
        command.addAll(List.of(arguments));
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        Thread cleanup = new Thread(() -> terminate(child), "benchmark-stage-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        try {
            System.out.printf("BENCHMARK_STAGE_PROCESS stage=%d pid=%d path=%s%n", stage, child.pid(), log);
            System.out.flush();
            if (!child.waitFor(5, TimeUnit.MINUTES))
                throw new IOException("Benchmark stage timed out: " + log);
            String output = Files.readString(log);
            System.out.print(output);
            System.out.flush();
            return new Result(child.exitValue(), output);
        } finally {
            terminate(child);
            Runtime.getRuntime().removeShutdownHook(cleanup);
        }
    }

    static SustainedThroughputWorker.Result sustained(int stage, String mode, Path root,
                                                     int concurrency, double offered, double fraction) throws Exception {
        Result result = run(SustainedStageWorker.class, stage, mode, root.toString(),
                Integer.toString(concurrency), Double.toString(offered), Double.toString(fraction));
        if (result.exitCode != 0) throw new IOException("Sustained stage exited " + result.exitCode
                + ": mode=" + mode + " stage=" + stage + " concurrency=" + concurrency + " load_fraction=" + fraction);
        Map<String, String> row = result.only("SUSTAINED_STAGE ");
        if (!mode.equals(row.get("mode")) || Integer.parseInt(row.get("concurrency")) != concurrency
                || Integer.parseInt(row.get("stage")) != stage
                || Double.parseDouble(row.get("load_fraction")) != fraction)
            throw new IOException("Sustained stage identity mismatch");
        return new SustainedThroughputWorker.Result(concurrency,
                Double.parseDouble(row.get("produce_per_s")), Boolean.parseBoolean(row.get("healthy")));
    }

    private static String executable(String name) {
        return Path.of(System.getProperty("java.home"), "bin",
                name + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "")).toString();
    }

    static void terminate(Process process) {
        if (!process.isAlive()) return;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try { process.waitFor(10, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    record Result(int exitCode, String output) {
        Map<String, String> only(String prefix) throws IOException {
            var rows = output.lines().filter(line -> line.startsWith(prefix)).toList();
            if (rows.size() != 1) throw new IOException("Expected one " + prefix + "marker; got " + rows.size());
            return fields(rows.getFirst());
        }

        boolean contains(String prefix) { return output.lines().anyMatch(line -> line.startsWith(prefix)); }
    }

    static Map<String, String> fields(String line) {
        return Arrays.stream(line.split("\\s+")).filter(field -> field.contains("="))
                .map(field -> field.split("=", 2)).collect(Collectors.toMap(pair -> pair[0], pair -> pair[1], (a, b) -> b));
    }
}
