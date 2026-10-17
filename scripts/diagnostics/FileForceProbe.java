package io.setu.storage.log;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/** Standalone Linux durability/pacing diagnostic; does not alter benchmark qualification. */
public final class FileForceProbe {
    public static void main(String[] args) throws Exception {
        if (!"Linux".equals(System.getProperty("os.name")))
            throw new IllegalArgumentException("Run on Linux to inspect fsync/fdatasync barriers");
        Path root = Files.createDirectories(Path.of(args[0]));
        Path data = Files.createTempFile(root, "mapped-", ".bin");
        Path capture = root.resolve("file-force-probe.jfr");
        try (var recording = new Recording()) {
            recording.enable("jdk.FileForce").withThreshold(Duration.ZERO);
            recording.start();
            barriers(data);
            recording.stop();
            recording.dump(capture);
        }
        List<Boolean> metadata = new ArrayList<>();
        try (var events = new RecordingFile(capture)) {
            while (events.hasMoreEvents()) {
                var event = events.readEvent();
                if (event.getEventType().getName().equals("jdk.FileForce")
                        && event.getString("path").equals(data.toString()))
                    metadata.add(event.getBoolean("metaData"));
            }
        }
        System.out.println("FILE_FORCE_BARRIERS metadata=" + metadata);
        if (!metadata.equals(List.of(true, true, false, true, false)))
            throw new AssertionError("Expected full barriers for creation/growth and data barriers for rewrites");
        delayedForce(root);
        Files.delete(data);
    }

    private static void barriers(Path data) throws Exception {
        try (var sync = new ChronicleFileSync(data);
             var writer = FileChannel.open(data, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var arena = Arena.ofConfined()) {
            sync.force(); // Empty file creation.
            var first = writer.map(FileChannel.MapMode.READ_WRITE, 0, 4096, arena);
            first.set(ValueLayout.JAVA_BYTE, 0, (byte) 1);
            sync.force(); // First allocation/growth through a different FD.
            first.set(ValueLayout.JAVA_BYTE, 0, (byte) 2);
            sync.force(); // Old header/index rewrite.
            var second = writer.map(FileChannel.MapMode.READ_WRITE, 4096, 4096, arena);
            second.set(ValueLayout.JAVA_BYTE, 4095, (byte) 3);
            sync.force(); // Growth while the original mapping is still live.
            first.set(ValueLayout.JAVA_BYTE, 0, (byte) 4);
            second.set(ValueLayout.JAVA_BYTE, 4095, (byte) 5);
            sync.force();
        }
        byte[] bytes = Files.readAllBytes(data);
        if (bytes.length != 8192 || bytes[0] != 4 || bytes[8191] != 5)
            throw new AssertionError("Mapped growth/header bytes lost");
        var closed = new ChronicleFileSync(data);
        closed.close();
        try { closed.force(); throw new AssertionError("Closed force acknowledged"); }
        catch (IOException expected) { }
    }

    private static void delayedForce(Path root) throws Exception {
        Path data = Files.createTempFile(root, "delayed-", ".bin");
        List<Long> requests = new ArrayList<>(), scheduled = new ArrayList<>();
        long interval = 6_400_000; // 256 records at 40,000 records/s; no reset after the stall.
        try (var sync = new ChronicleFileSync(data);
             var writer = FileChannel.open(data, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var arena = Arena.ofConfined()) {
            var mapping = writer.map(FileChannel.MapMode.READ_WRITE, 0, 4096, arena);
            sync.force();
            long due = System.nanoTime();
            for (int batch = 0; batch < 500; batch++, due += interval) {
                long remaining;
                while ((remaining = due - System.nanoTime()) > 0) LockSupport.parkNanos(remaining);
                long start = System.nanoTime();
                mapping.set(ValueLayout.JAVA_INT, 0, batch);
                if (batch == 100) {
                    long release = start + 383_000_000;
                    while ((remaining = release - System.nanoTime()) > 0) LockSupport.parkNanos(remaining);
                }
                sync.force(); // The delayed batch still requires its actual force before completion.
                long finish = System.nanoTime();
                requests.add(finish - start);
                scheduled.add(finish - due);
            }
            if (mapping.get(ValueLayout.JAVA_INT, 0) != 499) throw new AssertionError("Incomplete recovery");
        }
        double requestP99 = p99(requests), scheduledP99 = p99(scheduled);
        System.out.printf(java.util.Locale.ROOT,
                "DELAYED_FORCE batches=500 offered_records_per_s=40000 delay_ms=383 request_p99_ms=%.3f scheduled_p99_ms=%.3f last_scheduled_ms=%.3f%n",
                requestP99, scheduledP99, scheduled.getLast() / 1e6);
        if (scheduledP99 < 100 || scheduledP99 <= requestP99)
            throw new AssertionError("Scheduled latency lost the delayed force backlog");
        Files.delete(data);
    }

    private static double p99(List<Long> samples) {
        return samples.stream().sorted(Comparator.naturalOrder()).toList().get((int) Math.ceil(samples.size() * .99) - 1) / 1e6;
    }
}
