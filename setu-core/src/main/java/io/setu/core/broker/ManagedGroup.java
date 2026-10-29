package io.setu.core.broker;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ManagedGroup {
    final Map<Integer, Long> offsets = new ConcurrentHashMap<>();
    volatile List<String> members = List.of();
    volatile long generation;

    ManagedGroup(int partitions) {
        for (int partition = 0; partition < partitions; partition++) offsets.put(partition, 0L);
    }

    String owner(int partition) {
        return members.isEmpty() || partition < 0 ? "" : members.get(partition % members.size());
    }
}
