package io.setu.benchmark.chaos;

/** Process CPU time normalized to the CPUs available to this JVM, including affinity/container limits. */
final class ProcessCpuSampler {
    private final int processors;
    private long previousTime;
    private long previousCpu;

    ProcessCpuSampler(int processors, long time, long cpu) {
        if (processors <= 0) throw new IllegalArgumentException("processors must be positive");
        this.processors = processors;
        previousTime = time;
        previousCpu = cpu;
    }

    double sample(long time, long cpu) {
        long elapsed = time - previousTime;
        long used = cpu - previousCpu;
        boolean supported = previousCpu >= 0 && cpu >= 0;
        previousTime = time;
        previousCpu = cpu;
        if (!supported || elapsed <= 0 || used < 0) return -1;
        return Math.min(1.0, used / (double) elapsed / processors);
    }
}
