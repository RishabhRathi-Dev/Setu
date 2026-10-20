package io.setu.core.broker;

import io.setu.core.protocol.*;
import io.setu.storage.log.MessageLogs;
import io.setu.storage.log.StorageMode;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ConsumerMetadataTests {
    private DefaultBroker broker(int members, int partitions, long timeout) throws Exception {
        var broker = new DefaultBroker(StorageMode.IN_MEMORY, null, 1024,
                MessageLogs.DEFAULT_QUEUE_BLOCK_BYTES, timeout, timeout, 1024 * 1024, 0, 0,
                new ConsumerMetadataLimits(members, partitions));
        broker.execute(new BrokerRequest(1, Command.CREATE_TOPIC, "orders", -1, -1, -1, null, 2, null, null));
        return broker;
    }

    private BrokerRequest membership(Command command, String group) {
        return new BrokerRequest(1, command, "orders", -1, -1, -1, null, 0, group, "member");
    }

    @Test void departureAndStatelessCommitsDoNotAccumulateMetadata() throws Exception {
        try (var broker = broker(1, 2, 60_000)) {
            for (int i = 0; i < 200; i++) {
                String group = "group-" + i;
                broker.execute(new BrokerRequest(1, Command.COMMIT_OFFSET, "orders", 0, 0, 0, null, 0, group, null));
                broker.execute(membership(Command.JOIN_GROUP, group));
                broker.execute(membership(Command.JOIN_GROUP, group)); // idempotent even at capacity
                assertEquals(1, broker.activeConsumerCount());
                assertEquals(2, broker.activeConsumerPartitionMemberships());
                broker.execute(membership(Command.LEAVE_GROUP, group));
                broker.execute(membership(Command.LEAVE_GROUP, group));
                assertEquals(0, broker.activeConsumerCount());
                assertEquals(0, broker.activeConsumerPartitionMemberships());
            }
        }
    }

    @Test void memberAndPartitionLimitsRejectWithoutChangingExistingMembership() throws Exception {
        for (var limits : new int[][]{{1, 100}, {100, 3}}) {
            try (var broker = broker(limits[0], limits[1], 60_000)) {
                broker.execute(membership(Command.JOIN_GROUP, "first"));
                var failure = assertThrows(BrokerException.class,
                        () -> broker.execute(membership(Command.JOIN_GROUP, "rejected")));
                assertEquals(ErrorCode.QUOTA_EXCEEDED, failure.errorCode());
                assertEquals(1, broker.activeConsumerCount());
                assertEquals(2, broker.activeConsumerPartitionMemberships());
                broker.execute(membership(Command.HEARTBEAT, "first"));
                broker.execute(membership(Command.LEAVE_GROUP, "first"));
                broker.execute(membership(Command.JOIN_GROUP, "rejected"));
            }
        }
    }

    @Test void concurrentAdmissionCannotExceedCapacity() throws Exception {
        try (var broker = broker(3, 6, 60_000); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var results = new java.util.ArrayList<Future<Boolean>>();
            for (int i = 0; i < 30; i++) {
                String group = "group-" + i;
                results.add(workers.submit(() -> {
                    start.await();
                    try { broker.execute(membership(Command.JOIN_GROUP, group)); return true; }
                    catch (BrokerException e) { assertEquals(ErrorCode.QUOTA_EXCEEDED, e.errorCode()); return false; }
                }));
            }
            start.countDown();
            int admitted = 0;
            for (var result : results) if (result.get(3, TimeUnit.SECONDS)) admitted++;
            assertEquals(3, admitted);
            assertEquals(6, broker.activeConsumerPartitionMemberships());
        }
    }

    @Test void expirationReturnsMetadataCapacity() throws Exception {
        try (var broker = broker(1, 2, 100)) {
            broker.execute(membership(Command.JOIN_GROUP, "expired"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (broker.activeConsumerCount() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0, broker.activeConsumerPartitionMemberships());
            broker.execute(membership(Command.JOIN_GROUP, "replacement"));
            assertEquals(1, broker.activeConsumerCount());
        }
    }
}
