import io.setu.client.AsyncSetuClient;
import io.setu.client.SetuClient;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.ErrorCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Four concurrent partition writers, with one unresolved write per partition. */
public class SetuAsyncProducer {
    private static final int PARTITIONS = 4;
    private static final int RECORDS_PER_PARTITION = 32;

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9092;
        String topic = "async-" + UUID.randomUUID();
        String group = "async-readers";
        String member = UUID.randomUUID().toString();

        try (var reader = SetuClient.connectPlain(host, port, 5_000, 35_000, 16 * 1024 * 1024)) {
            checked(reader.createTopic(topic, PARTITIONS));
            // Join before producing so IN_MEMORY retains the records for verification.
            checked(reader.joinGroup(group, topic, member));
            try {
                try (var producer = new AsyncSetuClient(
                        () -> SetuClient.connectPlain(host, port, 5_000, 35_000, 16 * 1024 * 1024),
                        PARTITIONS, PARTITIONS, 0)) {
                    for (int sequence = 0; sequence < RECORDS_PER_PARTITION; sequence++) {
                        var writes = new ArrayList<CompletableFuture<BrokerResponse>>();
                        for (int partition = 0; partition < PARTITIONS; partition++) {
                            writes.add(producer.produce(topic, partition, value(partition, sequence)));
                        }
                        // Submit all four before waiting. The next wave bounds application work
                        // and preserves each partition's application order across pooled connections.
                        CompletableFuture.allOf(writes.toArray(CompletableFuture<?>[]::new)).join();
                        for (var write : writes) {
                            var response = checked(write.join());
                            if (response.offset() != sequence) throw new IOException("Unexpected assigned offset");
                        }
                    }
                    // Wait for every result before close; close cancels pending client work.
                }

                for (int partition = 0; partition < PARTITIONS; partition++) {
                    long next = 0;
                    while (next < RECORDS_PER_PARTITION) {
                        var page = checked(reader.fetch(group, member, topic, partition, next, 1024));
                        if (page.records().isEmpty()) throw new IOException("No progress at offset " + next);
                        for (var record : page.records()) {
                            if (record.offset() != next || !java.util.Arrays.equals(record.value(), value(partition, next)))
                                throw new IOException("Unexpected record at offset " + next);
                            next++;
                        }
                        checked(reader.acknowledge(group, member, topic, partition, next));
                    }
                    System.out.printf("partition=%d verified=%d nextOffset=%d%n", partition, next, next);
                }
                System.out.println("Verified 128 records through four pooled producer connections.");
            } finally {
                checked(reader.leaveGroup(group, member));
            }
        }
        // Ordinary writes are not retried: an I/O failure can leave an uncertain broker outcome.
    }

    private static byte[] value(int partition, long sequence) {
        return ("partition-" + partition + "/record-" + sequence).getBytes(StandardCharsets.UTF_8);
    }

    private static BrokerResponse checked(BrokerResponse response) throws IOException {
        if (response.errorCode() != ErrorCode.NONE)
            throw new IOException(response.errorCode() + ": " + response.errorMessage());
        return response;
    }
}
