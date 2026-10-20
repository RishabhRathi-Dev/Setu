import io.setu.client.SetuClient;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.ErrorCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Use separate producer/consumer connections so a blocking fetch does not block the producer. */
public class SetuLongPolling {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9092;
        String topic = "long-poll-" + UUID.randomUUID();
        String group = "waiting-readers";
        String member = UUID.randomUUID().toString();

        try (var producer = connect(host, port); var consumer = connect(host, port)) {
            checked(producer.createTopic(topic, 1));
            checked(consumer.joinGroup(group, topic, member));
            try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
                var started = new CountDownLatch(1);
                var pending = worker.submit(() -> {
                    started.countDown();
                    return checked(consumer.fetch(group, member, topic, 0, 0, 1024, 2_000));
                });
                if (!started.await(5, TimeUnit.SECONDS)) throw new IOException("Consumer did not start");
                // Data arriving before registration is also returned by the fetch.
                checked(producer.produce(topic, 0, "wake up".getBytes(StandardCharsets.UTF_8)));
                var page = pending.get(5, TimeUnit.SECONDS);
                if (page.records().size() != 1) throw new IOException("Expected one record");
                var record = page.records().getFirst();
                System.out.println("Long poll received: " + new String(record.value(), StandardCharsets.UTF_8));
                long next = record.offset() + 1;
                checked(consumer.acknowledge(group, member, topic, 0, next));

                var idle = checked(consumer.fetch(group, member, topic, 0, next, 1024, 250));
                if (!idle.records().isEmpty()) throw new IOException("Expected an empty timeout response");
                System.out.println("No new data: the next long poll timed out with an empty page.");
            } finally {
                checked(consumer.leaveGroup(group, member));
            }
        }
    }

    private static SetuClient connect(String host, int port) throws IOException {
        // The socket read timeout exceeds the longest long-poll wait.
        return SetuClient.connectPlain(host, port, 5_000, 35_000, 16 * 1024 * 1024);
    }

    private static BrokerResponse checked(BrokerResponse response) throws IOException {
        if (response.errorCode() != ErrorCode.NONE)
            throw new IOException(response.errorCode() + ": " + response.errorMessage());
        return response;
    }
}
