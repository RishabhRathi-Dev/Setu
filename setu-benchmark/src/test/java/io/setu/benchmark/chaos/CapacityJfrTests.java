package io.setu.benchmark.chaos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.time.Instant;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

class CapacityJfrTests {
    @Test
    void includesNativeBarriersWithoutDoubleCountingJdkForces() throws Exception {
        Path capture = directory.resolve("native-force.jfr");
        CapacityJfr.start(capture);
        try {
            var stage = new CapacityJfr.Stage();
            stage.mode = "FILE"; stage.stage = 1; stage.begin();
            var force = new io.setu.storage.uring.FileForceEvent();
            force.path = "/broker/@consumer-state/generation/cycle.cq4";
            force.metaData = false; force.begin();
            Thread.sleep(2);
            force.success = true; force.end(); force.commit();
            stage.end(); stage.commit();
        } finally { CapacityJfr.finish(); }
        var output = new java.io.ByteArrayOutputStream();
        var original = System.out;
        try (var printer = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setOut(printer); CapacityJfr.report(capture);
        } finally { System.setOut(original); }
        String report = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(report.contains("file_force_count=1 "), report);
        assertTrue(report.contains("scope=consumer_journal_data metadata=false count=1 "), report);
    }
    @TempDir Path directory;

    @Test
    void forceWaitsExcludeWarmupAndClipBoundaryCallsWithoutMergingConcurrentWaits() {
        var waits = new CapacityJfr.ForceWaits();
        Instant start = Instant.parse("2026-10-20T00:00:00Z");
        Instant end = start.plusMillis(100);
        waits.add(start.minusMillis(20), start, start, end); // Warmup only.
        waits.add(end, end.plusMillis(20), start, end); // Cleanup only.
        waits.add(start.minusMillis(20), start.plusMillis(30), start, end);
        waits.add(start.plusMillis(10), start.plusMillis(80), start, end);
        waits.add(start.plusMillis(80), end.plusMillis(50), start, end);
        assertEquals(3, waits.count());
        assertEquals(120_000_000, waits.totalNanos); // Overlapping calls count separately.
        assertEquals(70_000_000, waits.maxNanos);
        assertEquals(70, waits.p99Millis(), .1);
        var empty = new CapacityJfr.ForceWaits();
        assertEquals(0, empty.count());
        assertEquals(0, empty.p99Millis());
    }

    @Test
    void recordsFastFileForcesAndReportsOnlyTheMeasuredStage() throws Exception {
        Path capture = directory.resolve("force.jfr");
        CapacityJfr.start(capture);
        try (var channel = FileChannel.open(directory.resolve("force.bin"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[]{1}));
            channel.force(true); // Warmup force must be excluded.
            var stage = new CapacityJfr.Stage();
            stage.mode = "FILE";
            stage.stage = 1;
            stage.begin();
            channel.write(ByteBuffer.wrap(new byte[]{2}));
            channel.force(false);
            stage.end();
            stage.commit();
        } finally { CapacityJfr.finish(); }
        var output = new java.io.ByteArrayOutputStream();
        var original = System.out;
        try (var printer = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setOut(printer);
            assertEquals(Set.of(1), CapacityJfr.report(capture));
        } finally { System.setOut(original); }
        String report = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(report.contains("file_force_count=1 "), report);
        assertTrue(report.contains("file_force_total_ms="), report);
        assertTrue(report.contains("file_force_p99_ms="), report);
        assertTrue(report.contains("file_force_max_ms="), report);
        assertTrue(report.contains("CAPACITY_FORCE mode=FILE stage=1 scope=other metadata=false count=1 "), report);
    }

    @Test
    void separatesJournalAndPartitionBarriersWithinTheMeasuredStage() throws Exception {
        Path capture = directory.resolve("scopes.jfr");
        Path partition = Files.createDirectories(directory.resolve("topic/partition-0"));
        Path journal = Files.createDirectories(directory.resolve("@consumer-state/generation-1"));
        CapacityJfr.start(capture);
        try {
            var stage = new CapacityJfr.Stage();
            stage.mode = "FILE";
            stage.stage = 1;
            stage.begin();
            for (Path file : new Path[]{partition.resolve("cycle.cq4"), journal.resolve("cycle.cq4"),
                    journal.resolve("metadata.cq4t")}) {
                try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    channel.write(ByteBuffer.wrap(new byte[]{1}));
                    channel.force(true);
                    channel.write(ByteBuffer.wrap(new byte[]{2}));
                    channel.force(false);
                }
            }
            stage.end();
            stage.commit();
        } finally { CapacityJfr.finish(); }
        var output = new java.io.ByteArrayOutputStream();
        var original = System.out;
        try (var printer = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setOut(printer);
            CapacityJfr.report(capture);
        } finally { System.setOut(original); }
        String report = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(report.contains("file_force_count=6 "), report);
        for (String scope : new String[]{"partition_data", "consumer_journal_data", "consumer_journal_metadata"}) {
            for (boolean metadata : new boolean[]{true, false}) {
                assertTrue(report.contains("scope=" + scope + " metadata=" + metadata + " count=1 "), report);
            }
        }
    }

    @Test
    void failedCheckpointPreservesTheLastReadableRecordingAndExcludesWarmup() throws Exception {
        Path capture = directory.resolve("capacity.jfr");
        Path temporary = directory.resolve("capacity.jfr.part");
        CapacityJfr.start(capture);
        try {
            for (int stage : new int[]{0, 1, 8}) {
                var event = new CapacityJfr.Stage();
                event.mode = "IN_MEMORY";
                event.stage = stage;
                event.produced = stage * 100L;
                event.consumed = stage * 90L;
                event.begin();
                Thread.sleep(2);
                event.end();
                event.commit();
            }
            CapacityJfr.checkpoint();
            byte[] checkpoint = Files.readAllBytes(capture);
            assertTrue(checkpoint.length > 0);
            assertEquals(Set.of(1, 8), CapacityJfr.report(capture));
            var output = new java.io.ByteArrayOutputStream();
            var original = System.out;
            try (var printer = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)) {
                System.setOut(printer);
                CapacityJfr.report(capture);
            } finally { System.setOut(original); }
            String report = output.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(report.contains("produced=100 consumed=90 allocation_bytes_per_produced="));
            assertTrue(report.contains("produced=800 consumed=720 allocation_bytes_per_produced="));
            assertTrue(report.contains("allocation_bytes_per_consumed="));
            assertTrue(report.contains("gc_cycle_ms="));
            assertFalse(report.contains("NaN"));
            Files.createDirectory(temporary); // Make the next dump fail before replacement.
            assertThrows(IOException.class, CapacityJfr::checkpoint);
            assertArrayEquals(checkpoint, Files.readAllBytes(capture));
            assertEquals(Set.of(1, 8), CapacityJfr.report(capture));
        } finally {
            Files.deleteIfExists(temporary);
            CapacityJfr.finish();
        }
        assertEquals(Set.of(1, 8), CapacityJfr.report(capture));
    }
}
