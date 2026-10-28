package io.setu.benchmark.chaos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Supplement JVM CPU with the whole cgroup, including charged kernel-worker CPU. */
final class CgroupSample {
    static long[] read() throws Exception {
        String entry = Files.readAllLines(Path.of("/proc/self/cgroup")).stream()
                .filter(line -> line.startsWith("0::")).findFirst().orElseThrow().substring(3);
        long[] result = new long[2];
        for (String line : Files.readAllLines(Path.of("/sys/fs/cgroup" + entry, "cpu.stat"))) {
            String[] fields = line.split(" ");
            if (fields[0].equals("usage_usec")) result[0] = Long.parseLong(fields[1]);
            if (fields[0].equals("throttled_usec")) result[1] = Long.parseLong(fields[1]);
        }
        return result;
    }
    static void report(long[] start, long[] end, long duration) {
        System.out.printf(Locale.ROOT, "IO_CGROUP cpu_avg=%.6f throttled_ms=%.3f%n",
                (end[0] - start[0]) * 1000.0 / (duration * BenchmarkSettings.cpuCores()),
                (end[1] - start[1]) / 1000.0);
    }
}
