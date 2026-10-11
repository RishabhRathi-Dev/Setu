package io.setu.benchmark.chaos;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessCpuSamplerTests {
    @Test
    void normalizesCpuTimeToAvailableProcessorsAndClampsSamplingNoise() {
        ProcessCpuSampler sample = new ProcessCpuSampler(2, 0, 0);
        assertEquals(0.5, sample.sample(1_000_000_000, 1_000_000_000));
        assertEquals(1.0, sample.sample(2_000_000_000, 3_000_000_000L));
        assertEquals(1.0, sample.sample(3_000_000_000L, 5_100_000_000L));
        assertEquals(0.0, sample.sample(4_000_000_000L, 5_100_000_000L));
    }

    @Test
    void ignoresUnavailableCountersAndInvalidIntervals() {
        ProcessCpuSampler sample = new ProcessCpuSampler(2, 0, -1);
        assertEquals(-1, sample.sample(100, -1));
        assertEquals(-1, sample.sample(200, 10));
        assertEquals(0.5, sample.sample(300, 110));
        assertEquals(-1, sample.sample(300, 110));
    }
}
