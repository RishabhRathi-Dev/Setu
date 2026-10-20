package io.setu.benchmark.chaos;

import java.io.IOException;
import java.util.HashMap;

/** Separates a missed offered load from an invalid or failed steady workload. */
record SteadyMeasurement(long produced, long delivered, long missedRecords) {
    static SteadyMeasurement parse(String output, int partitions) throws IOException {
        var stages = output.lines().filter(line -> line.startsWith("STEADY_STAGE ")).toList();
        if (stages.size() != 1) throw new IOException("Expected exactly one completed STEADY_STAGE");
        var fields = new HashMap<String, String>();
        for (String field : stages.getFirst().substring("STEADY_STAGE ".length()).split("\\s+")) {
            String[] pair = field.split("=", 2);
            if (pair.length != 2 || fields.putIfAbsent(pair[0], pair[1]) != null)
                throw new IOException("Invalid or duplicate steady metric: " + field);
        }
        try {
            long actualPartitions = Long.parseLong(fields.get("partitions"));
            long produced = Long.parseLong(fields.get("produced"));
            long delivered = Long.parseLong(fields.get("delivered"));
            long missed = Long.parseLong(fields.get("missed_records"));
            long errors = Long.parseLong(fields.get("errors"));
            if (actualPartitions != partitions || produced <= 0 || delivered != produced || missed < 0 || errors != 0)
                throw new IOException("Steady workload failed: wrong partitions, no progress, delivery mismatch, or request errors");
            return new SteadyMeasurement(produced, delivered, missed);
        } catch (NumberFormatException invalid) {
            throw new IOException("Missing or invalid steady counter", invalid);
        }
    }
}
