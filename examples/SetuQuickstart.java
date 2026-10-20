import io.setu.client.SetuClient;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.ErrorCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Run against the local plaintext broker; see the root README for the classpath. */
public class SetuQuickstart {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9092;
        String topic = "quickstart";
        String group = "quickstart";
        String member = UUID.randomUUID().toString();

        try (var client = SetuClient.connectPlain(host, port, 5_000, 35_000, 16 * 1024 * 1024)) {
            var created = client.createTopic(topic, 1);
            if (created.errorCode() != ErrorCode.TOPIC_ALREADY_EXISTS) checked(created);
            // Join before producing: memory members begin at the current partition end.
            checked(client.joinGroup(group, topic, member));
            try {
                var appended = checked(client.produceBatch(topic, 0, List.of(
                        "hello, Setu".getBytes(StandardCharsets.UTF_8),
                        "ordered by partition".getBytes(StandardCharsets.UTF_8))));
                var page = checked(client.fetch(group, member, topic, 0, appended.offset(), 1024));
                if (page.records().size() != 2) throw new IOException("Expected two quickstart records");
                for (var record : page.records()) {
                    System.out.println(record.offset() + ": "
                            + new String(record.value(), StandardCharsets.UTF_8));
                }
                checked(client.acknowledge(group, member, topic, 0,
                        page.records().getLast().offset() + 1));
            } finally {
                checked(client.leaveGroup(group, member));
            }
        }
    }

    private static BrokerResponse checked(BrokerResponse response) throws IOException {
        if (response.errorCode() != ErrorCode.NONE) {
            throw new IOException(response.errorCode() + ": " + response.errorMessage());
        }
        return response;
    }
}
