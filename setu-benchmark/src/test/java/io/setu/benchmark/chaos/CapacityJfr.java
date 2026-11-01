package io.setu.benchmark.chaos;

import jdk.jfr.Configuration;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import org.HdrHistogram.Histogram;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Profile the worker, then analyze its recording in the parent JVM outside measured load. */
final class CapacityJfr {
    private static Recording recording;
    private static Path destination;

    public static void main(String[] args) throws java.io.IOException {
        if (args.length != 1) throw new IllegalArgumentException("Usage: CapacityJfr <recording.jfr|recordings-directory>");
        report(Path.of(args[0]));
    }

    static void start(Path path) throws Exception {
        destination = path;
        recording = new Recording(Configuration.getConfiguration("profile"));
        recording.setMaxSize(128L * 1024 * 1024);
        recording.setDumpOnExit(false);
        recording.enable(Stage.class).withThreshold(java.time.Duration.ZERO);
        recording.enable("jdk.FileForce").withThreshold(java.time.Duration.ZERO);
        recording.enable("io.setu.storage.FileForce").withThreshold(java.time.Duration.ZERO);
        recording.start();
    }

    static synchronized void checkpoint() throws java.io.IOException {
        if (recording != null) {
            Path temporary = destination.resolveSibling(destination.getFileName() + ".part");
            recording.dump(temporary);
            try {
                java.nio.file.Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                java.nio.file.Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static void finish() throws java.io.IOException {
        if (recording != null) {
            recording.stop();
            checkpoint();
            recording.close();
            recording = null;
        }
    }

    static void emergencyDump() {
        try { checkpoint(); }
        catch (Throwable ignored) { /* Preserve the last completed checkpoint if heap exhaustion prevents a dump. */ }
    }

    @Name("io.setu.benchmark.Stage")
    @Label("Setu measured capacity stage")
    static final class Stage extends Event {
        public String mode;
        public int stage;
        public long produced;
        public long consumed;
    }

    private static final class Summary {
        final String mode;
        final int stage;
        final Instant start;
        final Instant end;
        long allocationBytes;
        double gcPauseMillis;
        double gcCycleMillis;
        final long produced;
        final long consumed;
        long samples;
        final ForceWaits forceWaits = new ForceWaits();
        final Map<String, ForceWaits> forceFiles = new java.util.TreeMap<>();
        final Map<String, Long> methods = new HashMap<>();

        Summary(RecordedEvent event) {
            mode = event.getString("mode");
            stage = event.getInt("stage");
            start = event.getStartTime();
            end = event.getEndTime();
            produced = event.hasField("produced") ? event.getLong("produced") : 0;
            consumed = event.hasField("consumed") ? event.getLong("consumed") : 0;
        }

        boolean contains(Instant instant) { return !instant.isBefore(start) && instant.isBefore(end); }
    }

    /** Sum concurrent calls separately; clip each wait to the measured interval. */
    static final class ForceWaits {
        final Histogram durations = new Histogram(3);
        long totalNanos;
        long maxNanos;

        void add(Instant callStart, Instant callEnd, Instant stageStart, Instant stageEnd) {
            Instant start = callStart.isAfter(stageStart) ? callStart : stageStart;
            Instant end = callEnd.isBefore(stageEnd) ? callEnd : stageEnd;
            if (!end.isAfter(start)) return;
            long nanos = java.time.Duration.between(start, end).toNanos();
            durations.recordValue(nanos);
            totalNanos += nanos;
            maxNanos = Math.max(maxNanos, nanos);
        }

        long count() { return durations.getTotalCount(); }
        double p99Millis() { return Math.min(maxNanos, durations.getValueAtPercentile(99)) / 1e6; }
    }

    static java.util.Set<Integer> report(Path path) throws java.io.IOException {
        if (java.nio.file.Files.isDirectory(path)) {
            // Cross-JVM event IDs and tick origins differ; aggregate summaries, never JFR chunks.
            java.util.Set<Integer> result = new java.util.HashSet<>();
            try (var paths = java.nio.file.Files.list(path)) {
                for (Path recordingPath : paths.filter(file -> file.toString().endsWith(".jfr")).sorted().toList()) {
                    // A valid terminal OOM checkpoint may contain warmup but no completed measurement.
                    var stages = reportFile(recordingPath, true);
                    for (int stage : stages)
                        if (!result.add(stage)) throw new java.io.IOException("Duplicate JFR stage " + stage);
                }
            }
            if (result.isEmpty()) throw new java.io.IOException("JFR capture contains no completed measured stage: " + path);
            return result;
        }
        return reportFile(path, false);
    }

    private static java.util.Set<Integer> reportFile(Path path, boolean allowEmpty) throws java.io.IOException {
        List<Summary> stages = new ArrayList<>();
        try (RecordingFile file = new RecordingFile(path)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (event.getEventType().getName().equals("io.setu.benchmark.Stage") && event.getInt("stage") > 0)
                    stages.add(new Summary(event));
            }
        }
        // Recording events are not guaranteed to arrive in timestamp order.
        try (RecordingFile file = new RecordingFile(path)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                String type = event.getEventType().getName();
                if (!type.equals("jdk.ObjectAllocationSample") && !type.equals("jdk.ExecutionSample")
                        && !type.equals("jdk.GCPhasePause") && !type.equals("jdk.GarbageCollection")
                        && !isForce(type)) continue;
                for (Summary stage : stages) {
                    if (isForce(type)) {
                        stage.forceWaits.add(event.getStartTime(), event.getEndTime(), stage.start, stage.end);
                        String key = forceScope(event.getString("path")) + " metadata=" + event.getBoolean("metaData");
                        stage.forceFiles.computeIfAbsent(key, ignored -> new ForceWaits())
                                .add(event.getStartTime(), event.getEndTime(), stage.start, stage.end);
                    } else if (type.equals("jdk.GCPhasePause") || type.equals("jdk.GarbageCollection")) {
                        Instant start = event.getStartTime().isAfter(stage.start) ? event.getStartTime() : stage.start;
                        Instant end = event.getEndTime().isBefore(stage.end) ? event.getEndTime() : stage.end;
                        if (end.isAfter(start)) {
                            double millis = java.time.Duration.between(start, end).toNanos() / 1_000_000.0;
                            if (type.equals("jdk.GCPhasePause")) stage.gcPauseMillis += millis;
                            else stage.gcCycleMillis += millis;
                        }
                    } else if (stage.contains(event.getStartTime())) {
                        if (type.equals("jdk.ObjectAllocationSample")) stage.allocationBytes += event.getLong("weight");
                        else {
                            stage.samples++;
                            if (event.getStackTrace() != null) {
                                List<RecordedFrame> frames = event.getStackTrace().getFrames();
                                RecordedFrame frame = frames.stream().filter(f -> f.isJavaFrame()
                                        && f.getMethod().getType().getName().startsWith("io.setu.")).findFirst()
                                        .orElse(frames.isEmpty() ? null : frames.getFirst());
                                if (frame != null && frame.isJavaFrame()) stage.methods.merge(
                                        frame.getMethod().getType().getName() + "." + frame.getMethod().getName(), 1L, Long::sum);
                            }
                        }
                    }
                }
            }
        }
        stages.sort(java.util.Comparator.comparingInt(stage -> stage.stage));
        for (Summary stage : stages) {
            String hot = stage.methods.entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("unavailable");
            // Both ratios normalize the entire broker+client JVM estimate. They do not
            // attribute allocation exclusively to producers or exclusively to consumers.
            System.out.printf(Locale.ROOT, "CAPACITY_JFR mode=%s stage=%d duration_s=%.3f allocation_mb=%.3f gc_pause_ms=%.3f cpu_samples=%d hot_method=%s produced=%d consumed=%d allocation_bytes_per_produced=%.3f allocation_bytes_per_consumed=%.3f gc_cycle_ms=%.3f file_force_count=%d file_force_total_ms=%.6f file_force_p99_ms=%.6f file_force_max_ms=%.6f%n",
                    stage.mode, stage.stage, java.time.Duration.between(stage.start, stage.end).toNanos() / 1_000_000_000.0,
                    stage.allocationBytes / (1024.0 * 1024), stage.gcPauseMillis, stage.samples, hot,
                    stage.produced, stage.consumed,
                    stage.produced == 0 ? 0 : (double) stage.allocationBytes / stage.produced,
                    stage.consumed == 0 ? 0 : (double) stage.allocationBytes / stage.consumed,
                    stage.gcCycleMillis, stage.forceWaits.count(), stage.forceWaits.totalNanos / 1e6,
                    stage.forceWaits.p99Millis(), stage.forceWaits.maxNanos / 1e6);
            for (var entry : stage.forceFiles.entrySet()) {
                ForceWaits waits = entry.getValue();
                if (waits.count() == 0) continue;
                System.out.printf(Locale.ROOT, "CAPACITY_FORCE mode=%s stage=%d scope=%s count=%d total_ms=%.6f p99_ms=%.6f max_ms=%.6f%n",
                        stage.mode, stage.stage, entry.getKey(), waits.count(), waits.totalNanos / 1e6,
                        waits.p99Millis(), waits.maxNanos / 1e6);
            }
        }
        if (stages.isEmpty() && !allowEmpty) throw new java.io.IOException("JFR capture contains no completed measured stage: " + path);
        return stages.stream().map(stage -> stage.stage).collect(java.util.stream.Collectors.toSet());
    }

    private static boolean isForce(String type) {
        return type.equals("jdk.FileForce") || type.equals("io.setu.storage.FileForce");
    }

    private static String forceScope(String path) {
        if (path == null) return "other";
        String normalized = path.replace('\\', '/');
        String owner = normalized.contains("/@consumer-state/") ? "consumer_journal" : "partition";
        if (normalized.endsWith("/metadata.cq4t")) return owner + "_metadata";
        if (normalized.endsWith(".cq4")) return owner + "_data";
        return "other";
    }
}
