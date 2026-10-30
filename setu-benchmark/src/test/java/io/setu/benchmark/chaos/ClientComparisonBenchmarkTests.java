package io.setu.benchmark.chaos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, portable diagnostics. Every candidate gets a fresh broker/client pair and identical JVM limits. */
@EnabledIfSystemProperty(named = "setu.benchmark.clients", matches = "true")
class ClientComparisonBenchmarkTests {
    @Test void comparesClientsAcrossPoolsBatchesAndPartitions() throws Exception {
        int warmup = setting("warmup.seconds", 10, 1, 600);
        int seconds = setting("seconds", 30, 1, 3600);
        int repeats = setting("repeats", 2, 1, 20);
        int queue = setting("queue", 64, 1, 100_000);
        int backlog = setting("max.backlog.records", 0, 0, Integer.MAX_VALUE);
        var kinds = choices("kinds", "SHARED,POOLED,ASYNC_REQUEST,ASYNC_BATCH",
                Set.of("SHARED", "POOLED", "ASYNC_REQUEST", "ASYNC_BATCH"));
        var modes = choices("modes", "IN_MEMORY", Set.of("IN_MEMORY", "FILE"));
        Path root = Files.createDirectories(Path.of(System.getProperty("SETU_LOG_DIR", "logs"),
                "client-comparison", UUID.randomUUID().toString()).toAbsolutePath());
        sourceEvidence(root);
        System.out.println("CLIENT_COMPARISON_CAPTURE directory=" + root);
        var results = new StringBuilder();
        for (String mode : modes) for (int pool : numbers("pools", "1,4", 1, 1024))
            for (int batch : numbers("batches", "1,256", 1, 10_000))
                for (int partitions : numbers("partitions", "1,4", 1, 1024))
                    for (int repeat = 0; repeat < repeats; repeat++) {
                        if (backlog > 0 && backlog < batch) throw new IllegalArgumentException("Backlog must fit one batch");
                        // Rotate order between repetitions; never run candidates concurrently.
                        var order = new ArrayList<>(kinds);
                        Collections.rotate(order, -repeat % kinds.size());
                        for (String kind : order) {
                            int submitters = setting("submitters", pool * 2, 1, 4096);
                            Path capture = Files.createDirectory(root.resolve(
                                    mode + "-p" + pool + "-b" + batch + "-n" + partitions + "-r" + (repeat + 1) + "-" + kind));
                            results.append(run(capture, mode, kind, pool, batch, partitions,
                                    warmup, seconds, submitters, queue, backlog));
                            Files.writeString(root.resolve("results.txt"), results);
                        }
                    }
    }

    private static String run(Path capture, String mode, String kind, int pool, int batch,
            int partitions, int warmup, int seconds, int submitters, int queue, int backlog) throws Exception {
        Process broker = child(SteadyBrokerWorker.class, capture.resolve("broker.log"),
                mode, capture.toString(), capture.resolve("data").toString());
        Process client = null;
        try {
            await(capture.resolve("ready"), broker, 30);
            client = child(ClientComparisonWorker.class, capture.resolve("client.log"),
                    Files.readString(capture.resolve("ready")), kind, Integer.toString(pool), Integer.toString(batch),
                    Integer.toString(partitions), Integer.toString(warmup), Integer.toString(seconds),
                    Integer.toString(submitters), Integer.toString(queue), capture.toString(), Integer.toString(backlog));
            assertTrue(client.waitFor(warmup + seconds + 120L, TimeUnit.SECONDS), "Client timed out: " + capture);
            assertEquals(0, client.exitValue(), () -> read(capture.resolve("client.log")));
            await(capture.resolve("measure.done"), broker, 10);
            Files.writeString(capture.resolve("shutdown"), "shutdown");
            assertTrue(broker.waitFor(15, TimeUnit.SECONDS), "Broker shutdown timed out: " + capture);
            assertEquals(0, broker.exitValue(), () -> read(capture.resolve("broker.log")));
            String result = Files.readString(capture.resolve("result.txt"));
            assertTrue(result.startsWith("CLIENT_COMPARISON ") && result.lines().count() == 1, "Invalid result: " + result);
            System.out.print(result);
            return "capture=" + capture.getFileName() + " " + result;
        } finally {
            if (client != null && client.isAlive()) client.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            if (broker.isAlive()) broker.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static Process child(Class<?> worker, Path output, String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        BenchmarkSettings.addJvmArguments(command, 512);
        command.addAll(List.of("-Dsetu.log.level=WARN", "-DSETU_LOG_DIR=" + output.getParent().resolve("operational-" + output.getFileName()),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), worker.getName()));
        command.addAll(List.of(args));
        Files.write(output.resolveSibling(output.getFileName() + ".command.txt"), command);
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }

    private static void await(Path signal, Process process, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!Files.exists(signal) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(Files.exists(signal), "Worker failed or timed out waiting for " + signal);
    }

    private static int setting(String name, int fallback, int min, int max) {
        return BenchmarkSettings.integer("setu.benchmark.clients." + name, fallback, min, max);
    }

    private static List<Integer> numbers(String name, String fallback, int min, int max) {
        return choices(name, fallback, null).stream().map(value -> {
            int number = Integer.parseInt(value);
            if (number < min || number > max) throw new IllegalArgumentException(name + " must be " + min + ".." + max);
            return number;
        }).toList();
    }

    private static List<String> choices(String name, String fallback, Set<String> allowed) {
        var values = Arrays.stream(System.getProperty("setu.benchmark.clients." + name, fallback).split(","))
                .map(String::strip).toList();
        if (values.isEmpty() || values.stream().anyMatch(value -> value.isEmpty() || allowed != null && !allowed.contains(value))
                || new HashSet<>(values).size() != values.size()) throw new IllegalArgumentException("Invalid " + name);
        return values;
    }

    private static void sourceEvidence(Path root) throws Exception {
        Files.writeString(root.resolve("environment.txt"),
                "capture_start_utc=" + java.time.Instant.now() + "\nrevision=" + git("rev-parse", "HEAD") + "status=\n" + git("status", "--short")
                + "jdk=" + System.getProperty("java.runtime.version") + "\nos=" + System.getProperty("os.name")
                + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch")
                + "\ncpu=" + System.getenv("PROCESSOR_IDENTIFIER")
                + "\nhost_processors=" + Runtime.getRuntime().availableProcessors()
                + "\nfilesystem=" + Files.getFileStore(root).type()
                + "\nmedia=unknown\nOS_resource_quota=none\nqualified=false\n");
        Files.writeString(root.resolve("source.diff"), git("diff", "--binary", "HEAD"));
        var hashes = new StringBuilder();
        var digest = MessageDigest.getInstance("SHA-256");
        for (String name : git("ls-files", "--cached", "--others", "--exclude-standard").lines().sorted().distinct().toList()) {
            Path file = repository().resolve(name);
            if (Files.isRegularFile(file)) hashes.append(HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file))))
                    .append("  ").append(name).append('\n');
        }
        Files.writeString(root.resolve("source.sha256"), hashes);
    }

    private static String git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git")); command.addAll(List.of(args));
        // Surefire uses the module directory; source evidence and paths must be rooted in the checkout.
        var process = new ProcessBuilder(command).directory(repository().toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IllegalStateException(output);
        return output;
    }

    private static Path repository() {
        return Path.of(System.getProperty("maven.multiModuleProjectDirectory", "..")).toAbsolutePath().normalize();
    }

    private static String read(Path path) {
        try { return Files.readString(path); }
        catch (java.io.IOException error) { return error.toString(); }
    }
}
