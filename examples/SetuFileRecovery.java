import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.Command;
import io.setu.storage.log.FileWriteQueueOptions;
import io.setu.storage.log.MessageLogs;
import io.setu.storage.log.StorageMode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Write through a file broker, close it, then recover its topic, records and next offset. */
public class SetuFileRecovery {
    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args.length != 2)
            throw new IllegalArgumentException("Expected optional <file-write-queue-bytes> <file-write-queue-requests>; zero disables either limit");
        var writes = args.length == 0 ? FileWriteQueueOptions.defaults()
                : new FileWriteQueueOptions(Long.parseLong(args[0]), Integer.parseInt(args[1]));
        Path base = Files.createDirectories(Path.of("examples", "target"));
        Path data = Files.createTempDirectory(base, "recovery-").toAbsolutePath();
        System.out.println("Demo data: " + data);
        try (var broker = open(data, writes)) {
            broker.execute(new BrokerRequest(1, Command.CREATE_TOPIC, "orders", -1,
                    -1, -1, null, 1, null, null));
            for (int i = 0; i < 3; i++) {
                var response = broker.execute(new BrokerRequest(i + 2, Command.PRODUCE, "orders", 0,
                        -1, -1, ("order-" + i).getBytes(StandardCharsets.UTF_8), 0, null, null));
                if (response.offset() != i) throw new IllegalStateException("Unexpected append offset");
            }
        }

        try (var recovered = open(data, writes)) {
            var page = recovered.execute(new BrokerRequest(1, Command.FETCH, "orders", 0,
                    0, 1024, null, 0, null, null));
            if (page.records().size() != 3) throw new IllegalStateException("Expected three recovered records");
            for (var record : page.records()) {
                System.out.println("Recovered " + record.offset() + ": "
                        + new String(record.value(), StandardCharsets.UTF_8));
            }
            var appended = recovered.execute(new BrokerRequest(2, Command.PRODUCE, "orders", 0,
                    -1, -1, "after restart".getBytes(StandardCharsets.UTF_8), 0, null, null));
            if (appended.offset() != 3) throw new IllegalStateException("Offsets reset after restart");
            System.out.println("Next append after restart: " + appended.offset());
        }
        System.out.println("Partition for the cleanup example: " + data.resolve("orders/partition-0"));
    }

    private static DefaultBroker open(Path data, FileWriteQueueOptions writes) throws Exception {
        return new DefaultBroker(StorageMode.FILE, data, 1024, MessageLogs.DEFAULT_QUEUE_BLOCK_BYTES,
                DefaultBroker.DEFAULT_ACK_TIMEOUT_MILLIS, DefaultBroker.DEFAULT_CONSUMER_INACTIVITY_TIMEOUT_MILLIS,
                DefaultBroker.DEFAULT_MAX_RETAINED_MEMORY_BYTES, writes);
    }
}
