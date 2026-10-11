package io.setu.benchmark.chaos;

/** Process CPU time normalized to the allocated CPU budget, including fractional quotas. */
final class ProcessCpuSampler {
    private final double processors;
    private long previousTime;
    private long previousCpu;

    ProcessCpuSampler(double processors, long time, long cpu) {
        if (!Double.isFinite(processors) || processors <= 0) throw new IllegalArgumentException("processors must be finite and positive");
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
