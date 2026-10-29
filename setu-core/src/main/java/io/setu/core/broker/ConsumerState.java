package io.setu.core.broker;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ConsumerState {
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
