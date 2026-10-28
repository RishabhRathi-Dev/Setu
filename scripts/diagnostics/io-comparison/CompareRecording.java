package io.setu.benchmark.chaos;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.TreeMap;
import jdk.jfr.consumer.RecordingFile;
import org.HdrHistogram.Histogram;

/** Counts the same custom force event in every backend; native calls do not emit jdk.FileForce. */
public final class CompareRecording {
    private static final class Waits {
        final Histogram histogram = new Histogram(3);
        long total;
        long failures;
    }
    public static void main(String[] args) throws Exception {
        Path recording = Path.of(args[0]);
        Instant start = null, end = null;
        try (var input = new RecordingFile(recording)) {
            while (input.hasMoreEvents()) {
                var event = input.readEvent();
                if (event.getEventType().getName().equals("io.setu.benchmark.Stage")) {
                    if (start != null) throw new IllegalArgumentException("Expected exactly one measured stage");
                    start = event.getStartTime(); end = event.getEndTime();
                }
            }
        }
        if (start == null) throw new IllegalArgumentException("No measured stage");
        var scopes = new TreeMap<String, Waits>();
        boolean production = args.length > 1 && args[1].equals("production");
        long nativeForces = 0;
        try (var input = new RecordingFile(recording)) {
            while (input.hasMoreEvents()) {
                var event = input.readEvent();
                String type = event.getEventType().getName();
                if (production ? !type.equals("jdk.FileForce") && !type.equals("io.setu.storage.FileForce")
                        : !type.equals("io.setu.experiment.FileForce")) continue;
                Instant from = event.getStartTime().isAfter(start) ? event.getStartTime() : start;
                Instant to = event.getEndTime().isBefore(end) ? event.getEndTime() : end;
                if (!to.isAfter(from)) continue;
                String scope = production ? (event.getString("path").contains("@consumer-state") ? "journal" : "partition")
                        : event.getString("scope");
                if (type.equals("io.setu.storage.FileForce")) nativeForces++;
                Waits waits = scopes.computeIfAbsent(scope, ignored -> new Waits());
                long nanos = Duration.between(from, to).toNanos();
                waits.total += nanos;
                waits.histogram.recordValue(nanos);
                if (event.hasField("success") && !event.getBoolean("success")) waits.failures++;
            }
        }
        if (scopes.isEmpty()) throw new IllegalArgumentException("No force evidence in measured stage");
        if (production && nativeForces == 0) throw new IllegalArgumentException("Default fell back; no native force evidence");
        for (var entry : scopes.entrySet()) {
            Waits waits = entry.getValue();
            System.out.printf(Locale.ROOT, "IO_FORCE scope=%s count=%d total_ms=%.3f p99_ms=%.6f failures=%d%n",
                    entry.getKey(), waits.histogram.getTotalCount(), waits.total / 1e6,
                    waits.histogram.getValueAtPercentile(99) / 1e6, waits.failures);
            if (waits.failures != 0) throw new IllegalStateException("Force failures in measured stage");
        }
    }
}
