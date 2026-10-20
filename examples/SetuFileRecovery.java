import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.Command;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Write through a file broker, close it, then recover its topic, records and next offset. */
public class SetuFileRecovery {
    public static void main(String[] args) throws Exception {
        Path base = Files.createDirectories(Path.of("examples", "target"));
        Path data = Files.createTempDirectory(base, "recovery-").toAbsolutePath();
        System.out.println("Demo data: " + data);
        try (var broker = new DefaultBroker(data, 1024)) {
            broker.execute(new BrokerRequest(1, Command.CREATE_TOPIC, "orders", -1,
                    -1, -1, null, 1, null, null));
            for (int i = 0; i < 3; i++) {
                var response = broker.execute(new BrokerRequest(i + 2, Command.PRODUCE, "orders", 0,
                        -1, -1, ("order-" + i).getBytes(StandardCharsets.UTF_8), 0, null, null));
                if (response.offset() != i) throw new IllegalStateException("Unexpected append offset");
            }
        }

        try (var recovered = new DefaultBroker(data, 1024)) {
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
}
