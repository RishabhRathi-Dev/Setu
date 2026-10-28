package io.setu.benchmark.chaos;

import java.nio.file.Path;
import io.setu.storage.log.StorageMode;

/** One unchanged full-load stage through the real broker and TCP clients. */
public final class CompareWorker {
    public static void main(String[] args) throws Exception {
        BenchmarkRuntime.verifyZgc(CompareWorker.class);
        CapacityJfr.start(Path.of(args[1]));
        try { SustainedThroughputWorker.run(StorageMode.FILE, Path.of(args[0]), 2, 250000, 1); }
        finally { CapacityJfr.finish(); }
        System.out.println("IO_COMPARISON variant=" + System.getProperty("setu.io.variant", "default")
                + " qualified=false reason=single_load_comparison");
    }
}
