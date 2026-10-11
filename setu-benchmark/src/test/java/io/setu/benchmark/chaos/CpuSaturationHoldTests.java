package io.setu.benchmark.chaos;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CpuSaturationHoldTests {
    @Test
    void holdsForTheConfiguredDurationEvenIfCpuLaterDrops() {
        CpuSaturationHold hold = new CpuSaturationHold(0.92, 4, 10_000);
        assertFalse(hold.sample(0, 1.0));
        assertFalse(hold.sample(250, 1.0));
        assertFalse(hold.sample(500, 0.95));
        assertFalse(hold.sample(750, 1.0));
        assertTrue(hold.isHolding());
        assertEquals(10_750, hold.deadlineNanos());
        assertFalse(hold.sample(10_749, 0.5));
        assertTrue(hold.sample(10_750, 0.5));
    }

    @Test
    void rejectsTransientSpikesAndGapsBeforeStartingTheHold() {
        CpuSaturationHold hold = new CpuSaturationHold(0.92, 4, 10_000);
        for (int i = 0; i < 3; i++) assertFalse(hold.sample(i, 1));
        assertFalse(hold.sample(3, 0.8));
        for (int i = 4; i < 7; i++) assertFalse(hold.sample(i, 1));
        assertFalse(hold.sample(7, -1));
        for (int i = 8; i < 11; i++) assertFalse(hold.sample(i, 1));
        assertFalse(hold.isHolding());
    }

    @Test
    void zeroDurationPreservesImmediateStopAfterSustainedSaturation() {
        CpuSaturationHold hold = new CpuSaturationHold(0.92, 4, 0);
        for (int i = 0; i < 3; i++) assertFalse(hold.sample(i, 1));
        assertTrue(hold.sample(3, 1));
    }
}
