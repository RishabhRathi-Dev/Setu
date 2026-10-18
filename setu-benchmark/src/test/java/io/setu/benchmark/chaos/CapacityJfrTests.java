package io.setu.benchmark.chaos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CapacityJfrTests {
    @TempDir Path directory;

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
