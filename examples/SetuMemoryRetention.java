import io.setu.core.broker.BrokerException;
import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.Command;
import io.setu.core.protocol.ErrorCode;
import io.setu.storage.log.MessageLogs;
import io.setu.storage.log.StorageMode;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Let an unacknowledged memory record expire, then observe the explicit retention gap. */
public class SetuMemoryRetention {
    public static void main(String[] args) throws Exception {
        try (var broker = new DefaultBroker(StorageMode.IN_MEMORY, null, 1024,
                MessageLogs.DEFAULT_QUEUE_BLOCK_BYTES, 30_000, 60_000, 1024 * 1024, 250)) {
            broker.execute(new BrokerRequest(1, Command.CREATE_TOPIC, "orders", -1,
                    -1, -1, null, 1, null, null));
            broker.execute(new BrokerRequest(2, Command.JOIN_GROUP, "orders", -1,
                    -1, -1, null, 0, "readers", "slow"));
            try {
                broker.execute(new BrokerRequest(3, Command.PRODUCE, "orders", 0,
                        -1, -1, new byte[]{1}, 0, null, null));
                System.out.println("Produced offset 0; the member deliberately does not acknowledge it.");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    try {
                        broker.execute(new BrokerRequest(4, Command.FETCH, "orders", 0,
                                0, 1024, null, 0, "readers", "slow"));
                    } catch (BrokerException gap) {
                        if (gap.errorCode() != ErrorCode.OFFSET_OUT_OF_RANGE) throw gap;
                        System.out.println(gap.errorCode() + ": " + gap.getMessage());
                        System.out.println("Retained bytes after expiry: " + broker.retainedInMemoryBytes());
                        return;
                    }
                    Thread.sleep(25);
                }
                throw new IOException("Timed out waiting for memory retention");
            } finally {
                broker.execute(new BrokerRequest(5, Command.LEAVE_GROUP, null, -1,
                        -1, -1, null, 0, "readers", "slow"));
            }
        }
    }
}
