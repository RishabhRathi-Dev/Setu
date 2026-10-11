package io.setu.core.broker;

import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.Command;
import io.setu.core.protocol.ErrorCode;
import io.setu.storage.log.StorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class BrokerBackpressureTests {
    @TempDir Path directory;

    @Test
    void partitionsShareTheBudgetAndOnlyTheLastSlowMemberReleasesIt() throws Exception {
        try (var broker = new DefaultBroker(StorageMode.IN_MEMORY, null, 256, 1024, 5000, 10000, 24 * 1024)) {
            broker.execute(new BrokerRequest(1, Command.CREATE_TOPIC, "orders", -1, -1, -1, null, 2, null, null));
            for (String member : new String[] {"fast", "slow"})
                broker.execute(new BrokerRequest(2, Command.JOIN_GROUP, "orders", -1, -1, -1, null, 0, "g", member));
            assertEquals(0, broker.execute(batch(0)).offset());
            long retained = broker.retainedInMemoryBytes();
            assertTrue(retained > 0);
            var rejected = assertThrows(BrokerException.class, () -> broker.execute(batch(1)));
            assertEquals(ErrorCode.QUOTA_EXCEEDED, rejected.errorCode());
            assertEquals(retained, broker.retainedInMemoryBytes());
            var empty = broker.execute(new BrokerRequest(3, Command.FETCH, "orders", 1, 0, 8192, null, 0, null, null));
            assertTrue(empty.records().isEmpty());
            broker.execute(new BrokerRequest(4, Command.FETCH, "orders", 0, 0, 8192, null, 0, "g", "fast"));
            broker.execute(new BrokerRequest(5, Command.COMMIT_OFFSET, "orders", 0, 32, -1, null, 0, "g", "fast"));
            assertEquals(retained, broker.retainedInMemoryBytes(), "slow member still pins these records");
            broker.execute(new BrokerRequest(6, Command.LEAVE_GROUP, null, -1, -1, -1, null, 0, "g", "slow"));
            assertEquals(0, broker.retainedInMemoryBytes());
            assertEquals(0, broker.execute(batch(1)).offset(), "rejected batch must not consume offsets");
            assertTrue(broker.retainedInMemoryBytes() <= 24 * 1024);
        }
    }

    @Test
    void fileStorageDoesNotApplyTheMemoryLogBudget() throws Exception {
        try (var broker = new DefaultBroker(StorageMode.FILE, directory, 256, 1024, 5000, 10000, 1)) {
            broker.execute(new BrokerRequest(1, Command.CREATE_TOPIC, "orders", -1, -1, -1, null, 1, null, null));
            assertEquals(0, broker.execute(batch(0)).offset());
            assertEquals(0, broker.retainedInMemoryBytes());
        }
    }

    private static BrokerRequest batch(int partition) {
        return new BrokerRequest(7, Command.PRODUCE_BATCH, "orders", partition, -1, -1, null, 0, null, null,
                Collections.nCopies(32, new byte[256]));
    }
}
