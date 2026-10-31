package io.setu.benchmark.chaos;

import io.setu.storage.log.StorageMode;
import java.nio.file.Path;

/** Warm up and measure one load configuration continuously in one fresh JVM. */
final class SustainedStageWorker {
    public static void main(String[] args) throws Exception {
        BenchmarkRuntime.verifyZgc(SustainedStageWorker.class);
        if (Boolean.getBoolean("setu.benchmark.jfr"))
            CapacityJfr.start(Path.of(System.getProperty("setu.benchmark.stage.recording")));
        try {
            SustainedThroughputWorker.run(StorageMode.valueOf(args[0]), Path.of(args[1]),
                    Integer.parseInt(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]));
        } finally { CapacityJfr.finish(); }
    }
}
