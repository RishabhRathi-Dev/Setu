import io.setu.client.SetuClient;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.ErrorCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Publish batches to two explicit partitions, then fetch small pages and acknowledge each. */
public class SetuBatchAndPartitions {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9092;
        String topic = "batch-" + UUID.randomUUID();
        String group = "batch-readers";
        String member = UUID.randomUUID().toString();

        try (var client = SetuClient.connectPlain(host, port, 5_000, 35_000, 16 * 1024 * 1024)) {
            checked(client.createTopic(topic, 2));
            // Membership retains memory-mode data while both partitions are being consumed.
            checked(client.joinGroup(group, topic, member));
            try {
                for (int partition = 0; partition < 2; partition++) {
                    var values = List.of(bytes("order-A"), bytes("order-B"), bytes("order-C"));
                    long next = checked(client.produceBatch(topic, partition, values)).offset();
                    long end = next + values.size();
                    while (next < end) {
                        // 14 payload bytes fits two seven-byte records; records are never split.
                        var page = checked(client.fetch(group, member, topic, partition, next, 14));
                        if (page.records().isEmpty()) throw new IOException("No progress at offset " + next);
                        for (var record : page.records()) {
                            System.out.printf("partition=%d offset=%d value=%s%n", partition, record.offset(),
                                    new String(record.value(), StandardCharsets.UTF_8));
                        }
                        next = page.records().getLast().offset() + 1;
                        // Acknowledge only after successfully processing this page.
                        checked(client.acknowledge(group, member, topic, partition, next));
                    }
                }
            } finally {
                checked(client.leaveGroup(group, member));
            }
        }
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static BrokerResponse checked(BrokerResponse response) throws IOException {
        if (response.errorCode() != ErrorCode.NONE)
            throw new IOException(response.errorCode() + ": " + response.errorMessage());
        return response;
    }
}
