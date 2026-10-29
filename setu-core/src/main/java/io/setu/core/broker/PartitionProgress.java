package io.setu.core.broker;

import java.util.Map;

final class PartitionProgress {
    volatile int waiters;
    volatile boolean hasMembers;
    final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
    final java.util.concurrent.locks.Condition appended = lock.newCondition();
    final java.util.TreeMap<Long, Integer> offsets = new java.util.TreeMap<>();
    final Map<ConsumerKey, Long> members = new java.util.HashMap<>();
    void removeOffset(long offset) {
        offsets.computeIfPresent(offset, (ignored, count) -> count == 1 ? null : count - 1);
    }
}
