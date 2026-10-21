package io.setu.core.protocol;

import java.util.Map;

public record GroupAssignment(long generation, Map<Integer, Long> nextOffsets) {
    public GroupAssignment {
        if (generation < 0) throw new IllegalArgumentException("Negative generation");
        nextOffsets = Map.copyOf(nextOffsets);
    }
}
