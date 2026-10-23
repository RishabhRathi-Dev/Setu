package io.setu.core.broker;

import io.setu.storage.log.MessageLog;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Mutable broker state; locking is owned by DefaultBroker. */
final class BrokerState {
    private BrokerState() { }
    record TopicState(List<MessageLog> partitions, List<PartitionProgress> progress) {
        TopicState(List<MessageLog> partitions) {
            this(partitions, partitions.stream().map(ignored -> new PartitionProgress()).toList());
        }
    }
    static final class PartitionProgress {
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
    record ConsumerKey(String groupId, String memberId) { }
    record GroupKey(String groupId, String topic) { }
    static final class ManagedGroup {
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
    static final class ConsumerState {
        final String topic;
        final boolean managed;
        final Map<Integer, Long> nextOffsets;
        final Map<Integer, Long> fetchedOffsets;
        final Map<Integer, Long> pendingAckSince = new ConcurrentHashMap<>();
        volatile long lastActivityNanos;

        ConsumerState(String topic, Map<Integer, Long> nextOffsets, Map<Integer, Long> fetchedOffsets, boolean managed) {
            this.topic = topic;
            this.managed = managed;
            this.nextOffsets = nextOffsets;
            this.fetchedOffsets = fetchedOffsets;
        }

        void touch(long now) { lastActivityNanos = now; }

        synchronized void recordFetch(int partition, long nextFetchedOffset, long now) {
            long fetched = fetchedOffsets.merge(partition, nextFetchedOffset, Math::max);
            if (fetched > nextOffsets.getOrDefault(partition, 0L)) pendingAckSince.putIfAbsent(partition, now);
            touch(now);
        }

        void acknowledged(int partition, long nextOffset, long previousOffset, long now) {
            if (nextOffset >= fetchedOffsets.getOrDefault(partition, 0L)) pendingAckSince.remove(partition);
            else if (nextOffset > previousOffset) pendingAckSince.put(partition, now);
            touch(now);
        }

        boolean expired(long now, long ackTimeoutNanos, long inactivityTimeoutNanos) {
            if (now - lastActivityNanos >= inactivityTimeoutNanos) return true;
            for (long since : pendingAckSince.values())
                if (now - since >= ackTimeoutNanos) return true;
            return false;
        }
    }
}
