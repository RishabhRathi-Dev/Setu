package io.setu.benchmark.chaos;

import io.setu.client.SetuClient;
import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.ErrorCode;
import io.setu.security.authorization.InMemoryAuthorizer;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.BrokerRequestHandler;
import io.setu.server.tcp.TcpServer;
import io.setu.storage.log.StorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Failure scenarios over real TCP sockets with multiple independent consumer members. */
class MultiConsumerChaosTests {
    private static final int MAX_FRAME = 16 * 1024 * 1024;
    private static final String TOPIC = "chaos";
    private static final String GROUP = "chaos-group";

    @TempDir Path tempDir;

    @Test
    void inMemoryModeExpiresStalledMemberAndLosesRecordsAfterBrokerRestart() throws Exception {
        exerciseChaos(StorageMode.IN_MEMORY);
    }

    @Test
    void fileModeExpiresStalledMemberAndRecoversRecordsAfterBrokerRestart() throws Exception {
        exerciseChaos(StorageMode.FILE);
    }

    private void exerciseChaos(StorageMode mode) throws Exception {
        Path data = tempDir.resolve(mode.name());
        DefaultBroker broker = new DefaultBroker(mode, mode == StorageMode.FILE ? data : null,
                1024, 1024 * 1024, 250, 5_000);
        TcpServer server = start(broker);
        int port = server.localAddress().getPort();
        List<SetuClient> liveClients = new ArrayList<>();
        try {
            SetuClient producer = client(port, liveClients);
            SetuClient fastOne = client(port, liveClients);
            SetuClient fastTwo = client(port, liveClients);
            SetuClient lagging = client(port, liveClients);
            assertEquals(ErrorCode.NONE, producer.createTopic(TOPIC, 1).errorCode());
            sendTruncatedFrame(port); // abrupt TCP close halfway through a length-prefixed request
            assertEquals(1, producer.metadata().topics().get(TOPIC),
                    "a malformed partial frame must not take down the broker");
            join(fastOne, "fast-one");
            join(fastTwo, "fast-two");
            join(lagging, "lagging");

            for (int i = 0; i < 8; i++)
                assertEquals(ErrorCode.NONE, producer.produce(TOPIC, 0, bytes("record-" + i)).errorCode());
            assertEquals(8, fetch(fastOne, "fast-one").records().size());
            assertEquals(8, fetch(fastTwo, "fast-two").records().size());
            assertEquals(8, fetch(lagging, "lagging").records().size()); // this member never acknowledges
            assertEquals(ErrorCode.NONE, fastOne.acknowledge(GROUP, "fast-one", TOPIC, 0, 8).errorCode());
            assertEquals(ErrorCode.NONE, fastTwo.acknowledge(GROUP, "fast-two", TOPIC, 0, 8).errorCode());

            lagging.close(); // inject a consumer connection loss while its acknowledgement is outstanding
            liveClients.remove(lagging);
            Thread.sleep(350); // pass the configured acknowledgement deadline
            assertEquals(ErrorCode.NONE, producer.produce(TOPIC, 0, bytes("after-expiry")).errorCode());
            try (SetuClient stale = client(port)) {
                assertEquals(ErrorCode.BAD_REQUEST, stale.heartbeat(GROUP, "lagging").errorCode());
            }

            // Both surviving consumers continue from their acknowledged offsets after the stalled member is evicted.
            assertEquals("after-expiry", text(fastOne.fetch(GROUP, "fast-one", TOPIC, 0, 8, 1024).records().getFirst().value()));
            assertEquals("after-expiry", text(fastTwo.fetch(GROUP, "fast-two", TOPIC, 0, 8, 1024).records().getFirst().value()));

            // A broker outage closes active client sockets; restart then exposes storage-mode-specific recovery.
            server.close();
            assertThrows(IOException.class, producer::metadata);
            broker.close();
            liveClients.forEach(MultiConsumerChaosTests::closeQuietly);
            liveClients.clear();

            broker = new DefaultBroker(mode, mode == StorageMode.FILE ? data : null,
                    1024, 1024 * 1024, 250, 5_000);
            server = start(broker);
            try (SetuClient recovered = client(server.localAddress().getPort())) {
                if (mode == StorageMode.FILE) {
                    assertEquals(1, recovered.metadata().topics().get(TOPIC));
                    var history = recovered.fetch(TOPIC, 0, 0, 1024 * 1024).records();
                    assertEquals(9, history.size());
                    assertEquals("after-expiry", text(history.getLast().value()));
                } else {
                    assertTrue(recovered.metadata().topics().isEmpty());
                    assertEquals(ErrorCode.NONE, recovered.createTopic(TOPIC, 1).errorCode());
                    assertTrue(recovered.fetch(TOPIC, 0, 0, 1024).records().isEmpty());
                }
            }
        } finally {
            server.close();
            broker.close();
            liveClients.forEach(MultiConsumerChaosTests::closeQuietly);
        }
    }

    private static TcpServer start(DefaultBroker broker) throws IOException {
        TcpServer server = new TcpServer(new InetSocketAddress("127.0.0.1", 0),
                new FramedConnectionHandler(new BrokerRequestHandler(broker)));
        server.start();
        return server;
    }

    private static SetuClient client(int port, List<SetuClient> clients) throws IOException {
        SetuClient client = client(port);
        clients.add(client);
        return client;
    }

    private static SetuClient client(int port) throws IOException {
        return SetuClient.connectPlain("127.0.0.1", port, 2_000, 3_000, MAX_FRAME);
    }

    private static void sendTruncatedFrame(int port) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port);
             DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
            output.writeInt(4_096);
            output.write(new byte[]{0x53, 0x45, 0x54});
            output.flush();
        }
    }

    private static void join(SetuClient client, String member) throws IOException {
        assertEquals(ErrorCode.NONE, client.joinGroup(GROUP, TOPIC, member).errorCode());
    }

    private static io.setu.core.protocol.BrokerResponse fetch(SetuClient client, String member) throws IOException {
        return client.fetch(GROUP, member, TOPIC, 0, 0, 1024 * 1024);
    }

    private static byte[] bytes(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value, java.nio.charset.StandardCharsets.UTF_8); }
    private static void closeQuietly(SetuClient client) {
        try { client.close(); } catch (IOException ignored) { }
    }
}
