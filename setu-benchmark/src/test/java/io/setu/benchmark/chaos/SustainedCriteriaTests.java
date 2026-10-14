package io.setu.benchmark.chaos;

import io.setu.storage.log.StorageMode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SustainedCriteriaTests {
    @Test void capsConcurrencyWithoutRoundingAboveFractionalCpuAllocation() {
        assertEquals(2, SustainedCriteria.concurrencyLimit(1.6, 32768));
        assertEquals(8, SustainedCriteria.concurrencyLimit(4, 32768));
        assertEquals(4, SustainedCriteria.concurrencyLimit(16, 4));
        assertEquals(1, SustainedCriteria.concurrencyLimit(.4, 8));
    }
    @Test void requiresPositiveStableWarmupThroughput() {
        assertTrue(SustainedCriteria.stable(List.of(100., 102., 101., 103.)));
        assertFalse(SustainedCriteria.stable(List.of(100., 100., 150., 150.)));
        assertFalse(SustainedCriteria.stable(List.of(0., 0., 0., 0.)));
        assertFalse(SustainedCriteria.stable(List.of(100., 100.)));
    }
    @Test void rejectsBacklogCpuErrorsMissedLoadAndLatencyViolations() {
        assertTrue(healthy(1000, 950, 0, 0, 4, .85, 0, 1));
        assertFalse(healthy(1000, 949, 0, 0, 4, .85, 0, 1));
        assertFalse(healthy(0, 1000, 0, 0, 4, .85, 0, 1));
        assertFalse(healthy(1000, 1000, 1, 0, 4, .85, 0, 1));
        assertFalse(healthy(949, 949, 0, 51, 4, .85, 0, 1));
        assertFalse(healthy(1000, 1000, 0, 0, 4, .851, 0, 1));
        assertFalse(healthy(1000, 1000, 0, 0, 50, .8, 0, 1));
        assertFalse(healthy(1000, 1000, 0, 0, 5, .8, 0, .5));
        assertFalse(healthy(1000, 1000, 0, 0, 4, .8, 2, 1));
    }
    @Test void estimatesHeapTrendAcrossGcSawtooth() {
        long m = 1024 * 1024;
        assertEquals(1, SustainedCriteria.heapSlope(List.of(10*m, 11*m, 12*m), List.of(0L, 1_000_000_000L, 2_000_000_000L)));
        assertEquals(0, SustainedCriteria.heapSlope(List.of(10*m, 20*m, 10*m), List.of(0L, 1_000_000_000L, 2_000_000_000L)));
    }
    @Test void estimatesHeapTrendUsingIrregularTimesRatherThanSampleIndexes() {
        long m = 1024 * 1024;
        assertEquals(1, SustainedCriteria.heapSlope(List.of(10*m, 11*m, 20*m), List.of(0L, 1_000_000_000L, 10_000_000_000L)));
        assertEquals(Double.POSITIVE_INFINITY, SustainedCriteria.heapSlope(List.of(m, 2*m), List.of(0L, 0L)));
        assertEquals(Double.POSITIVE_INFINITY, SustainedCriteria.heapSlope(List.of(m, 2*m), List.of(0L)));
    }
    private boolean healthy(long produced, long consumed, long errors, long missed, double p99, double cpu,
                            double slope, double fraction) {
        return SustainedCriteria.healthy(StorageMode.IN_MEMORY, produced, consumed, errors, missed, p99,
                cpu, 512L * 1024 * 1024, slope, true, 1000, produced, fraction);
    }
}
