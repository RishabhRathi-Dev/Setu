package io.setu.core.broker;

import io.setu.core.protocol.*;
import io.setu.storage.log.StorageMode;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LongPollRetentionTests {
    @Test void racingLongPollRegistrationAndAppendNeverLosesAWakeup() throws Exception {
        try (var broker = new DefaultBroker(1024); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            broker.execute(request(Command.CREATE_TOPIC, 0, null));
            broker.execute(request(Command.JOIN_GROUP, 0, "reader"));
            for (int i = 0; i < 200; i++) {
                int offset = i;
                var start = new CyclicBarrier(2);
                var fetch = workers.submit(() -> { start.await(); return broker.execute(request(Command.FETCH, offset, "reader").withWaitMillis(5000)); });
                var append = workers.submit(() -> { start.await(); return broker.execute(request(Command.PRODUCE, 0, null)); });
                assertEquals(offset, append.get(2, TimeUnit.SECONDS).offset());
                var records = fetch.get(2, TimeUnit.SECONDS).records();
                assertEquals(1, records.size());
                assertEquals(offset, records.getFirst().offset());
                broker.execute(request(Command.COMMIT_OFFSET, offset + 1, "reader"));
            }
            assertEquals(0, broker.retainedInMemoryBytes());
        }
    }
    @Test void leavingAGroupWakesAndRejectsItsPendingLongPoll() throws Exception {
        try (var broker = new DefaultBroker(1024); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            broker.execute(request(Command.CREATE_TOPIC, 0, null));
            broker.execute(request(Command.JOIN_GROUP, 0, "reader"));
            var fetch = workers.submit(() -> broker.execute(request(Command.FETCH, 0, "reader").withWaitMillis(30000)));
            assertThrows(TimeoutException.class, () -> fetch.get(50, TimeUnit.MILLISECONDS));
            broker.execute(request(Command.LEAVE_GROUP, 0, "reader"));
            var failure = assertThrows(ExecutionException.class, () -> fetch.get(2, TimeUnit.SECONDS));
            assertInstanceOf(BrokerException.class, failure.getCause());
            assertEquals(ErrorCode.BAD_REQUEST, ((BrokerException) failure.getCause()).errorCode());
        }
    }
    @Test void closingTheBrokerWakesLongPolls() throws Exception {
        var broker = new DefaultBroker(1024);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            broker.execute(request(Command.CREATE_TOPIC, 0, null));
            var pending = workers.submit(() -> broker.execute(request(Command.FETCH, 0, null).withWaitMillis(30000)));
            assertThrows(TimeoutException.class, () -> pending.get(50, TimeUnit.MILLISECONDS));
            broker.close();
            var failure = assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            assertInstanceOf(java.io.IOException.class, failure.getCause());
        } finally { broker.close(); }
    }
    private static BrokerRequest request(Command command, long offset, String member) {
        return new BrokerRequest(1, command, "orders", 0, offset, 1024, new byte[]{1},
                2, member == null ? null : "g", member);
    }
    @Test void longPollWakesOnAppendAndTimesOutWithoutPolling() throws Exception {
        try (var broker = new DefaultBroker(1024); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            broker.execute(request(Command.CREATE_TOPIC, 0, null));
            broker.execute(request(Command.JOIN_GROUP, 0, "reader"));
            var result = workers.submit(() -> broker.execute(request(Command.FETCH, 0, "reader").withWaitMillis(2000)));
            assertThrows(TimeoutException.class, () -> result.get(50, TimeUnit.MILLISECONDS));
            broker.execute(request(Command.PRODUCE, 0, null));
            assertEquals(1, result.get(2, TimeUnit.SECONDS).records().size());
            long start = System.nanoTime();
            assertTrue(broker.execute(request(Command.FETCH, 1, "reader").withWaitMillis(100)).records().isEmpty());
            assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(80));
        }
    }
    @Test void timedRetentionReleasesUnacknowledgedMessagesAndReportsTheGap() throws Exception {
        try (var broker = new DefaultBroker(StorageMode.IN_MEMORY, null, 1024, 4096, 5000, 10000, 1024 * 1024, 80)) {
            broker.execute(request(Command.CREATE_TOPIC, 0, null));
            broker.execute(request(Command.JOIN_GROUP, 0, "slow"));
            broker.execute(request(Command.PRODUCE, 0, null));
            assertTrue(broker.retainedInMemoryBytes() > 0);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (broker.retainedInMemoryBytes() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0, broker.retainedInMemoryBytes(), "expiry must run without incoming requests or ack");
            var gap = assertThrows(BrokerException.class, () -> broker.execute(request(Command.FETCH, 0, "slow")));
            assertEquals(ErrorCode.OFFSET_OUT_OF_RANGE, gap.errorCode());
            assertTrue(gap.getMessage().contains("offset 1"));
        }
    }
    @Test void acknowledgementOnOnePartitionDoesNotReleaseAnother() throws Exception {
        try (var broker = new DefaultBroker(1024)) {
            broker.execute(request(Command.CREATE_TOPIC, 0, null));
            broker.execute(request(Command.JOIN_GROUP, 0, "reader"));
            broker.execute(request(Command.PRODUCE, 0, null));
            broker.execute(new BrokerRequest(2, Command.PRODUCE, "orders", 1, -1, -1, new byte[]{2}, 0, null, null));
            broker.execute(request(Command.FETCH, 0, "reader"));
            broker.execute(request(Command.COMMIT_OFFSET, 1, "reader"));
            assertEquals(1, broker.execute(new BrokerRequest(3, Command.FETCH, "orders", 1, 0, 1024, null, 0, "g", "reader")).records().size());
        }
    }
}
