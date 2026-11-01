package io.setu.benchmark.chaos;

import java.util.List;

/** Settings shared by the isolated workers; launchers can supply a host-sized budget. */
final class BenchmarkSettings {
    static final List<String> PROPERTIES = List.of("setu.benchmark.record.bytes",
            "setu.benchmark.heap.mib", "setu.benchmark.direct.mib", "setu.benchmark.cpu.cores",
            "setu.benchmark.memory.log.bytes", "setu.benchmark.unbounded.admission", "setu.benchmark.disk.reserve.bytes", "java.io.tmpdir",
            "setu.benchmark.target.profile");

    private BenchmarkSettings() { }

    static int recordBytes() { return integer("setu.benchmark.record.bytes", 1024, 1, 65_536); }

    static int integer(String property, int fallback, int minimum, int maximum) {
        int value = Integer.parseInt(System.getProperty(property, Integer.toString(fallback)));
        if (value < minimum || value > maximum)
            throw new IllegalArgumentException(property + " must be " + minimum + ".." + maximum);
        return value;
    }

    static double cpuCores() {
        double value = Double.parseDouble(System.getProperty("setu.benchmark.cpu.cores",
                Integer.toString(Runtime.getRuntime().availableProcessors())));
        if (!Double.isFinite(value) || value <= 0)
            throw new IllegalArgumentException("setu.benchmark.cpu.cores must be finite and positive");
        return value;
    }

    static void addJvmArguments(List<String> command, int defaultHeapMiB) {
        command.addAll(io.setu.storage.runtime.JvmOptions.arguments());
        command.add("-Xms64m");
        command.add("-Xmx" + integer("setu.benchmark.heap.mib", defaultHeapMiB, 128, 1_048_576) + "m");
        command.add("-XX:MaxDirectMemorySize=" + integer("setu.benchmark.direct.mib", 128, 16, 1_048_576) + "m");
        if (System.getProperty("setu.benchmark.cpu.cores") != null)
            command.add("-XX:ActiveProcessorCount=" + (int) Math.ceil(cpuCores()));
        for (String property : PROPERTIES) {
            String value = System.getProperty(property);
            if (value != null) command.add("-D" + property + "=" + value);
        }
    }
}
