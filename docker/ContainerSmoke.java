import io.setu.client.SetuClient;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.ErrorCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Exercise the packaged client against the container, including records recovered after restart. */
public class ContainerSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2 || !List.of("write", "read").contains(args[0])) {
            throw new IllegalArgumentException("Expected write|read [port]");
        }
        boolean write = args[0].equals("write");
        int port = args.length == 2 ? Integer.parseInt(args[1]) : 9092;
        String member = UUID.randomUUID().toString();
        List<String> expected = List.of("durable container record", "ordered batch record");
        try (var client = SetuClient.connectPlain("127.0.0.1", port, 5_000, 35_000, 16 * 1024 * 1024)) {
            if (write) checked(client.createTopic("container-smoke", 1));
            checked(client.joinGroup("container-smoke", "container-smoke", member));
            try {
                if (write) {
                    var response = checked(client.produceBatch("container-smoke", 0,
                            expected.stream().map(value -> value.getBytes(StandardCharsets.UTF_8)).toList()));
                    if (response.offset() != 0) throw new IOException("Expected initial offset zero");
                }
                var page = checked(client.fetch("container-smoke", member, "container-smoke", 0, 0, 1024));
                if (page.records().size() != expected.size()) throw new IOException("Missing durable records");
                for (int i = 0; i < expected.size(); i++) {
                    var record = page.records().get(i);
                    if (record.offset() != i || !expected.get(i).equals(new String(record.value(), StandardCharsets.UTF_8))) {
                        throw new IOException("Recovered record or offset mismatch");
                    }
                }
                checked(client.acknowledge("container-smoke", member, "container-smoke", 0, expected.size()));
            } finally {
                checked(client.leaveGroup("container-smoke", member));
            }
        }
        System.out.println(write ? "Durable write verified" : "Recovered records verified");
    }

    private static BrokerResponse checked(BrokerResponse response) throws IOException {
        if (response.errorCode() != ErrorCode.NONE) {
            throw new IOException(response.errorCode() + ": " + response.errorMessage());
        }
        return response;
    }
}
