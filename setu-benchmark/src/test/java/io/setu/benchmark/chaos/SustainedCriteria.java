package io.setu.benchmark.chaos;

import io.setu.storage.log.StorageMode;
import java.util.List;

/** Explicit, shared definitions for a sustainable stage and warmup convergence. */
final class SustainedCriteria {
    static int concurrencyLimit(double cores, int requested) {
        int hostLimit = Math.max(1, (int) Math.floor(2 * cores));
        return Integer.highestOneBit(Math.min(hostLimit, requested));
    }

    static boolean stable(List<Double> rates) {
        if (rates.size() < 4) return false;
        int half = rates.size() / 2;
        double first = rates.subList(0, half).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double last = rates.subList(half, rates.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return first > 0 && last > 0 && Math.abs(last - first) / Math.max(first, last) <= .05;
    }

    static boolean healthy(StorageMode mode, long produced, long consumed, long errors, long missed,
                           double p99Millis, double cpu, long heapPeak, double heapSlopeMiB,
                           boolean warmed, double offered, double actual, double loadFraction) {
        double latencyLimit = loadFraction == .5 ? (mode == StorageMode.FILE ? 10 : 5)
                : (mode == StorageMode.FILE ? 100 : 50);
        return produced > 0 && consumed >= produced * .95 && errors == 0 && missed >= 0
                && p99Millis >= 0 && p99Millis < latencyLimit && cpu >= 0 && cpu <= .85
                && heapPeak <= 1024L * 1024 * 1024 && heapSlopeMiB <= 1.0 && warmed
                && (offered == 0 || actual >= offered * .95);
    }

    /** Least-squares used-heap slope against actual sample times, in MiB/s. */
    static double heapSlope(List<Long> samples, List<Long> timesNanos) {
        if (samples.size() < 2 || samples.size() != timesNanos.size()) return Double.POSITIVE_INFINITY;
        long origin = timesNanos.getFirst();
        double meanX = timesNanos.stream().mapToDouble(time -> (time - origin) / 1e9).average().orElseThrow();
        double meanY = samples.stream().mapToDouble(Long::doubleValue).average().orElseThrow();
        double covariance = 0, variance = 0;
        for (int i = 0; i < samples.size(); i++) {
            if (i > 0 && timesNanos.get(i) <= timesNanos.get(i - 1)) return Double.POSITIVE_INFINITY;
            double x = (timesNanos.get(i) - origin) / 1e9 - meanX;
            covariance += x * (samples.get(i) - meanY);
            variance += x * x;
        }
        return variance <= 0 ? Double.POSITIVE_INFINITY : covariance / variance / (1024 * 1024);
    }
}
