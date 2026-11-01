package io.setu.benchmark.chaos;

import io.setu.storage.log.StorageMode;
import java.nio.file.Files;
import java.nio.file.Path;

/** Fixed-load contributor diagnostic; deliberately does not qualify sustained capacity. */
final class ContributorThroughputWorker {
    public static void main(String[] args) throws Exception {
        BenchmarkRuntime.verifyZgc(ContributorThroughputWorker.class);
        if (!"smoke".equals(System.getProperty("setu.benchmark.measurement"))
                || BenchmarkSettings.recordBytes() != 1024
                || BenchmarkSettings.integer("setu.benchmark.batch.records", 256, 1, 10000) != 256
                || BenchmarkSettings.integer("setu.benchmark.stage.millis", 3000, 250, 60000) != 10000
                || BenchmarkSettings.integer("setu.benchmark.warmup.millis", 2000, 250, 60000) != 5000)
            throw new IllegalArgumentException("Contributor diagnostics require 1 KiB records, 256-record batches, 5s warmup and 10s measurement");
        StorageMode mode = StorageMode.valueOf(args[0]);
        Path root = Files.createDirectories(Path.of(args[1]));
        double target = mode == StorageMode.IN_MEMORY ? 500000 : 250000;
        int pairs = mode == StorageMode.IN_MEMORY ? 1 : 2;
        System.out.printf("CONTRIBUTOR_CONFIG mode=%s filesystem=%s durability=%s jvm_scope=stage%n", mode,
                Files.getFileStore(root).type(), mode == StorageMode.FILE ? "force_each_group_commit_before_ack" : "volatile");
        int stage = 0;
        for (double fraction : new double[]{.5, .8, 1})
            BenchmarkStageProcess.sustained(++stage, mode.name(), root, pairs, target * fraction, fraction);
        System.out.printf("CONTRIBUTOR_END mode=%s qualified=false%n", mode);
    }
}
