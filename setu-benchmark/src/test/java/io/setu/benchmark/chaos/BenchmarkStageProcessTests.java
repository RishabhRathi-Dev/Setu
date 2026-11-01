package io.setu.benchmark.chaos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Verify actual process isolation and that independently recorded stages remain analyzable. */
class BenchmarkStageProcessTests {
    @TempDir Path temporary;

    @Test
    void isolatesStaticStateAndAnalyzesDistinctStageRecordings() throws Exception {
        String previous = System.getProperty("SETU_LOG_DIR");
        System.setProperty("SETU_LOG_DIR", temporary.toString());
        try {
            var first = BenchmarkStageProcess.run(Probe.class, 1);
            var second = BenchmarkStageProcess.run(Probe.class, 2);
            assertEquals(0, first.exitCode());
            assertEquals(0, second.exitCode());
            var one = first.only("STAGE_PROBE ");
            var two = second.only("STAGE_PROBE ");
            assertNotEquals(one.get("pid"), two.get("pid"));
            assertEquals("1", one.get("initializations"));
            assertEquals("1", two.get("initializations"));
            assertEquals("1", one.get("stage"));
            assertEquals("2", two.get("stage"));
            assertEquals(Set.of(1, 2), CapacityJfr.report(temporary.resolve("recordings")));
            assertTrue(Files.isRegularFile(temporary.resolve("stages/0001/worker.log")));
            assertTrue(Files.isRegularFile(temporary.resolve("stages/0002/worker.log")));
        } finally {
            if (previous == null) System.clearProperty("SETU_LOG_DIR");
            else System.setProperty("SETU_LOG_DIR", previous);
        }
    }

    @Test
    void fixedLoadsWarmUpAndMeasureInDistinctJvmsForBothStorageModes() throws Exception {
        Map<String, String> settings = Map.of("setu.benchmark.measurement", "smoke",
                "setu.benchmark.stage.millis", "500", "setu.benchmark.warmup.millis", "250",
                "setu.benchmark.jfr", "true", "setu.benchmark.batch.records", "256",
                "setu.benchmark.record.bytes", "1024", "setu.log.level", "ERROR");
        Map<String, String> previous = new HashMap<>();
        settings.forEach((key, value) -> { previous.put(key, System.getProperty(key)); System.setProperty(key, value); });
        previous.put("SETU_LOG_DIR", System.getProperty("SETU_LOG_DIR"));
        try {
            for (String mode : new String[]{"IN_MEMORY", "FILE"}) {
                Path capture = Files.createDirectories(temporary.resolve(mode));
                Path data = Files.createDirectories(capture.resolve("data"));
                System.setProperty("SETU_LOG_DIR", capture.toString());
                int stage = 0;
                Set<String> identities = new java.util.HashSet<>();
                for (double fraction : new double[]{.5, .8, 1}) {
                    BenchmarkStageProcess.sustained(++stage, mode, data, 1, 10000 * fraction, fraction);
                    var output = new BenchmarkStageProcess.Result(0,
                            Files.readString(capture.resolve(String.format("stages/%04d/worker.log", stage))));
                    var warmup = output.only("SUSTAINED_WARMUP ");
                    var measured = output.only("SUSTAINED_STAGE ");
                    assertEquals(warmup.get("jvm_pid"), measured.get("jvm_pid"));
                    assertTrue(identities.add(measured.get("jvm_pid")));
                    assertEquals("0", measured.get("errors"));
                    assertTrue(Long.parseLong(measured.get("produced")) > 0);
                    assertTrue(Long.parseLong(measured.get("consumed")) > 0);
                }
                assertEquals(Set.of(1, 2, 3), CapacityJfr.report(capture.resolve("recordings")));
            }
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) System.clearProperty(key);
                else System.setProperty(key, value);
            });
        }
    }

    @Test
    void retainsUnexpectedExitAndOutput() throws Exception {
        String previous = System.getProperty("SETU_LOG_DIR");
        System.setProperty("SETU_LOG_DIR", temporary.toString());
        try {
            var result = BenchmarkStageProcess.run(Probe.class, 1, "fail");
            assertEquals(7, result.exitCode());
            assertTrue(result.output().contains("probe failure evidence"));
            assertThrows(java.io.IOException.class, () -> result.only("SUSTAINED_STAGE "));
        } finally {
            if (previous == null) System.clearProperty("SETU_LOG_DIR");
            else System.setProperty("SETU_LOG_DIR", previous);
        }
    }

    public static final class Probe {
        private static int initializations;

        public static void main(String[] args) throws Exception {
            if (args.length > 0) {
                System.out.println("probe failure evidence");
                System.exit(7);
            }
            CapacityJfr.start(Path.of(System.getProperty("setu.benchmark.stage.recording")));
            try {
                CapacityJfr.Stage stage = new CapacityJfr.Stage();
                stage.mode = "IN_MEMORY";
                stage.stage = Integer.parseInt(System.getProperty("setu.benchmark.stage.id"));
                stage.begin();
                Thread.sleep(100);
                stage.commit();
                System.out.printf("STAGE_PROBE stage=%d pid=%d initializations=%d%n",
                        stage.stage, ProcessHandle.current().pid(), ++initializations);
            } finally { CapacityJfr.finish(); }
        }
    }
}
