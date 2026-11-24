package io.setu.benchmark.chaos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProgressiveCapacityProgressTests {
    @Test
    void bothDirectionsMustProgressDuringMeasurementToContinueTheRamp() {
        assertNull(ProgressiveCapacityChaosWorker.progressStopReason(4096, 8192, 0, 4096));
    }

    @Test
    void lateCompletionInTheMissingDirectionEstablishesAClientStall() {
        assertEquals("CLIENT_STALL", ProgressiveCapacityChaosWorker.progressStopReason(0, 266240, 4096, 0));
        assertEquals("CLIENT_STALL", ProgressiveCapacityChaosWorker.progressStopReason(4096, 0, 0, 4096));
    }

    @Test
    void idleWindowsAndUncorroboratedMissingProgressFailTheWorker() {
        assertEquals("WORKER_FAILURE", ProgressiveCapacityChaosWorker.progressStopReason(0, 0, 4096, 4096));
        assertEquals("WORKER_FAILURE", ProgressiveCapacityChaosWorker.progressStopReason(0, 8192, 0, 4096));
        assertEquals("WORKER_FAILURE", ProgressiveCapacityChaosWorker.progressStopReason(4096, 0, 4096, 0));
    }
}
