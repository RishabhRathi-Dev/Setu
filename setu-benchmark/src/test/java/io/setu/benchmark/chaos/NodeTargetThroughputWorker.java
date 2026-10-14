package io.setu.benchmark.chaos;

import io.setu.storage.log.StorageMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Verify a specified node target with the same measured load and criteria as the capacity profile. */
final class NodeTargetThroughputWorker {
    public static void main(String[] args) throws Exception {
        BenchmarkRuntime.verifyZgc(NodeTargetThroughputWorker.class);
        if (!"full".equals(System.getProperty("setu.benchmark.measurement")) || BenchmarkSettings.recordBytes() != 1024)
            throw new IllegalArgumentException("Node targets require the full profile and 1 KiB records");
        if (BenchmarkSettings.integer("setu.benchmark.stage.millis", 3000, 250, 60000) < 30000
                || BenchmarkSettings.integer("setu.benchmark.warmup.millis", 2000, 250, 60000) < 10000)
            throw new IllegalArgumentException("Node targets require at least 10s warmup and 30s measurement");
        StorageMode mode = StorageMode.valueOf(args[0]);
        Path root = Files.createDirectories(Path.of(args[1]));
        double target = mode == StorageMode.IN_MEMORY ? 500000 : 250000;
        int concurrency = mode == StorageMode.IN_MEMORY ? 1 : 2;
        if (concurrency > SustainedCriteria.concurrencyLimit(BenchmarkSettings.cpuCores(), 32768))
            throw new IllegalArgumentException("CPU budget is too small for the target concurrency");
        System.out.printf(Locale.ROOT, "NODE_TARGET_CONFIG mode=%s record_bytes=1024 target_per_s=%.0f concurrency=%d cpu_cores=%.3f heap_mib=%d filesystem=%s durability=%s%n",
                mode, target, concurrency, BenchmarkSettings.cpuCores(), Runtime.getRuntime().maxMemory() / (1024 * 1024),
                Files.getFileStore(root).type(), mode == StorageMode.FILE ? "force_each_group_commit_before_ack" : "volatile");
        if (Boolean.getBoolean("setu.benchmark.jfr"))
            CapacityJfr.start(Path.of(System.getProperty("SETU_LOG_DIR", "logs"), "node-target.jfr"));
        boolean healthy = true;
        try {
            for (double fraction : new double[]{.5, .8, 1})
                healthy &= SustainedThroughputWorker.run(mode, root, concurrency, target * fraction, fraction).healthy();
        } finally { CapacityJfr.finish(); }
        System.out.printf("NODE_TARGET_END mode=%s qualified=%s%n", mode, healthy);
    }
}
