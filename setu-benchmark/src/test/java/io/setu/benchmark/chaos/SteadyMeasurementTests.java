package io.setu.benchmark.chaos;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SteadyMeasurementTests {
    private static final String STAGE = "STEADY_STAGE partitions=1 produced=28736 delivered=28736 "
            + "missed_records=1280 errors=0 produce_per_s=9536.0 scheduled_completion_p99_ms=217.217";

    @Test
    void preservesMissedLoadWithoutTreatingDeliveredRecordsAsLost() throws Exception {
        var measured = SteadyMeasurement.parse("BENCHMARK_RUNTIME worker=SteadyLoadWorker\n" + STAGE, 1);
        assertEquals(28736, measured.produced());
        assertEquals(measured.produced(), measured.delivered());
        assertEquals(1280, measured.missedRecords());
        assertEquals(0, SteadyMeasurement.parse(STAGE.replace("missed_records=1280", "missed_records=0"), 1).missedRecords());
    }

    @Test
    void rejectsRequestErrorsLostDeliveriesNoProgressAndInvalidCounters() {
        for (String invalid : List.of(
                STAGE.replace("errors=0", "errors=1"),
                STAGE.replace("delivered=28736", "delivered=28735"),
                STAGE.replace("28736", "0"),
                STAGE.replace("missed_records=1280", "missed_records=-1"),
                STAGE.replace("errors=0", "errors=NaN"),
                STAGE.replace("errors=0", ""),
                STAGE.replace("produced=28736", "produced=9223372036854775808"),
                STAGE.replace("partitions=1", "partitions=4"))) {
            assertThrows(IOException.class, () -> SteadyMeasurement.parse(invalid, 1), invalid);
        }
    }

    @Test
    void rejectsMissingDuplicateAndMalformedStageOutput() {
        for (String invalid : List.of("worker failed", STAGE + "\n" + STAGE,
                STAGE + " errors=0", STAGE + " incomplete")) {
            assertThrows(IOException.class, () -> SteadyMeasurement.parse(invalid, 1), invalid);
        }
    }
}
