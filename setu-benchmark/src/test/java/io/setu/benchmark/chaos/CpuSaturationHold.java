package io.setu.benchmark.chaos;

/** Holds a fixed load after consecutive high-CPU samples; later CPU dips remain observable. */
final class CpuSaturationHold {
    private final double threshold;
    private final int requiredSamples;
    private final long durationNanos;
    private int consecutiveSamples;
    private boolean holding;
    private long started;

    CpuSaturationHold(double threshold, int requiredSamples, long durationNanos) {
        if (threshold <= 0 || threshold > 1 || requiredSamples <= 0 || durationNanos < 0)
            throw new IllegalArgumentException("Invalid CPU hold configuration");
        this.threshold = threshold;
        this.requiredSamples = requiredSamples;
        this.durationNanos = durationNanos;
    }

    /** Returns true only once saturation has been observed and the hold window has elapsed. */
    boolean sample(long now, double cpu) {
        if (!holding) {
            consecutiveSamples = cpu >= threshold ? consecutiveSamples + 1 : 0;
            if (consecutiveSamples < requiredSamples) return false;
            started = now;
            holding = true;
        }
        return now - started >= durationNanos;
    }

    boolean isHolding() { return holding; }
    long startedNanos() { return started; }
    long deadlineNanos() { return started + durationNanos; }
}
