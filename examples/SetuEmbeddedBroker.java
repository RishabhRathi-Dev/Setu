import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.Command;

import java.nio.charset.StandardCharsets;

/** Call the broker directly and observe acknowledgement-based memory reclamation. */
public class SetuEmbeddedBroker {
    public static void main(String[] args) throws Exception {
        try (var broker = new DefaultBroker(1024)) {
            broker.execute(new BrokerRequest(1, Command.CREATE_TOPIC, "orders", -1,
                    -1, -1, null, 1, null, null));
            broker.execute(new BrokerRequest(2, Command.JOIN_GROUP, "orders", -1,
                    -1, -1, null, 0, "readers", "embedded"));
            try {
                var appended = broker.execute(new BrokerRequest(3, Command.PRODUCE, "orders", 0,
                        -1, -1, "embedded order".getBytes(StandardCharsets.UTF_8), 0, null, null));
                System.out.println("Retained bytes before acknowledgement: " + broker.retainedInMemoryBytes());
                var page = broker.execute(new BrokerRequest(4, Command.FETCH, "orders", 0,
                        appended.offset(), 1024, null, 0, "readers", "embedded"));
                for (var record : page.records()) {
                    System.out.println(record.offset() + ": " + new String(record.value(), StandardCharsets.UTF_8));
                }
                if (page.records().size() != 1) throw new IllegalStateException("Expected one record");
                broker.execute(new BrokerRequest(5, Command.COMMIT_OFFSET, "orders", 0,
                        page.records().getLast().offset() + 1, 0, null, 0, "readers", "embedded"));
                if (broker.retainedInMemoryBytes() != 0) throw new IllegalStateException("Expected memory reclamation");
                System.out.println("Retained bytes after acknowledgement: " + broker.retainedInMemoryBytes());
            } finally {
                broker.execute(new BrokerRequest(6, Command.LEAVE_GROUP, null, -1,
                        -1, -1, null, 0, "readers", "embedded"));
            }
        }
    }
}
