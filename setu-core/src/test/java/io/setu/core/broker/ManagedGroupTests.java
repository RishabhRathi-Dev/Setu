package io.setu.core.broker;

import io.setu.core.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ManagedGroupTests {
    @TempDir Path directory;

    private BrokerRequest member(Command command, String member) {
        return new BrokerRequest(1, command, "orders", 1, 0, 1024, null, 4, "group", member);
    }

    @Test void expiryReassignsOwnershipAndDoesNotReviveTheExpiredMemberOnRestart() throws Exception {
        try (var broker = new DefaultBroker(io.setu.storage.log.StorageMode.FILE, directory, 1024, 2000, 400)) {
            broker.execute(member(Command.CREATE_TOPIC, "a"));
            broker.execute(member(Command.JOIN_MANAGED_GROUP, "a"));
            var joined = broker.execute(member(Command.JOIN_MANAGED_GROUP, "b")).assignment();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (broker.activeConsumerCount() > 1 && System.nanoTime() < deadline) {
                broker.execute(member(Command.HEARTBEAT, "b"));
                Thread.sleep(20);
            }
            assertEquals(1, broker.activeConsumerCount());
            var remaining = broker.execute(member(Command.GROUP_ASSIGNMENT, "b")).assignment();
            assertEquals(4, remaining.nextOffsets().size());
            assertTrue(remaining.generation() > joined.generation());
        }
        try (var broker = new DefaultBroker(directory, 1024)) {
            assertEquals(1, broker.activeConsumerCount());
            assertThrows(BrokerException.class, () -> broker.execute(member(Command.GROUP_ASSIGNMENT, "a")));
            assertEquals(4, broker.execute(member(Command.GROUP_ASSIGNMENT, "b")).assignment().nextOffsets().size());
        }
    }

    @Test void assignmentsFenceRevokedOwnersAndPreserveGroupOffsetsAcrossDepartureAndRestart() throws Exception {
        try (var broker = new DefaultBroker(directory, 1024)) {
            broker.execute(member(Command.CREATE_TOPIC, "a"));
            var first = broker.execute(member(Command.JOIN_MANAGED_GROUP, "a")).assignment();
            assertEquals(4, first.nextOffsets().size());
            broker.execute(new BrokerRequest(1, Command.PRODUCE, "orders", 1, -1, -1, new byte[]{1}, 0, null, null));
            broker.execute(member(Command.FETCH, "a").withGeneration(first.generation()));
            broker.execute(new BrokerRequest(1, Command.COMMIT_OFFSET, "orders", 1, 1, 0, null, 0, "group", "a")
                    .withGeneration(first.generation()));
            var second = broker.execute(member(Command.JOIN_MANAGED_GROUP, "b")).assignment();
            assertEquals(java.util.Set.of(1, 3), second.nextOffsets().keySet());
            assertEquals(1, second.nextOffsets().get(1));
            assertEquals(ErrorCode.STALE_GENERATION, assertThrows(BrokerException.class,
                    () -> broker.execute(member(Command.FETCH, "a").withGeneration(first.generation()))).errorCode());
            assertEquals(ErrorCode.STALE_GENERATION, assertThrows(BrokerException.class,
                    () -> broker.execute(member(Command.COMMIT_OFFSET, "a").withGeneration(first.generation()))).errorCode());
            broker.execute(member(Command.LEAVE_GROUP, "a"));
            var remaining = broker.execute(member(Command.GROUP_ASSIGNMENT, "b")).assignment();
            assertEquals(4, remaining.nextOffsets().size());
            assertTrue(remaining.generation() > second.generation());
            broker.execute(member(Command.LEAVE_GROUP, "b"));
        }
        try (var broker = new DefaultBroker(directory, 1024)) {
            var assigned = broker.execute(member(Command.JOIN_MANAGED_GROUP, "c")).assignment();
            assertEquals(4, assigned.nextOffsets().size());
            assertEquals(1, assigned.nextOffsets().get(1));
            assertThrows(BrokerException.class, () -> broker.execute(member(Command.JOIN_GROUP, "explicit")));
        }
    }
}
