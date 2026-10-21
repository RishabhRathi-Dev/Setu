package io.setu.integration;

import io.setu.client.SetuClient;
import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.ErrorCode;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.BrokerRequestHandler;
import io.setu.server.tcp.TcpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConsumerRecoveryIntegrationTests {
    @TempDir Path directory;

    @Test void restartRestoresOnlyAcknowledgedProgressAndDepartureIsDurable() throws Exception {
        try (var broker = new DefaultBroker(directory, 1024); var server = start(broker);
             var client = connect(server)) {
            assertEquals(ErrorCode.NONE, client.createTopic("orders", 2).errorCode());
            assertEquals(ErrorCode.NONE, client.joinGroup("group", "orders", "member").errorCode());
            assertEquals(ErrorCode.NONE, client.produceBatch("orders", 0,
                    List.of(new byte[]{1}, new byte[]{2}, new byte[]{3})).errorCode());
            assertEquals(3, client.fetch("group", "member", "orders", 0, 0, 1024).records().size());
            assertEquals(ErrorCode.NONE, client.acknowledge("group", "member", "orders", 0, 2).errorCode());
            assertEquals(2, client.fetchOffset("group", "member", "orders", 0).offset());
        }
        try (var broker = new DefaultBroker(directory, 1024); var server = start(broker);
             var client = connect(server)) {
            assertEquals(1, broker.activeConsumerCount());
            assertEquals(2, broker.activeConsumerPartitionMemberships());
            assertEquals(ErrorCode.NONE, client.heartbeat("group", "member").errorCode());
            assertEquals(2, client.fetchOffset("group", "member", "orders", 0).offset());
            assertEquals(0, client.fetchOffset("group", "member", "orders", 1).offset());
            assertEquals(ErrorCode.BAD_REQUEST,
                    client.acknowledge("group", "member", "orders", 0, 3).errorCode());
            assertEquals(1, client.fetch("group", "member", "orders", 0, 2, 1024).records().size());
            assertEquals(ErrorCode.NONE, client.acknowledge("group", "member", "orders", 0, 3).errorCode());
            assertEquals(ErrorCode.NONE, client.leaveGroup("group", "member").errorCode());
        }
        try (var broker = new DefaultBroker(directory, 1024); var server = start(broker);
             var client = connect(server)) {
            assertEquals(0, broker.activeConsumerCount());
            assertEquals(ErrorCode.BAD_REQUEST, client.fetchOffset("group", "member", "orders", 0).errorCode());
        }
    }

    private TcpServer start(DefaultBroker broker) throws Exception {
        var server = new TcpServer(new InetSocketAddress("127.0.0.1", 0),
                new FramedConnectionHandler(16 * 1024 * 1024, new BrokerRequestHandler(broker)));
        server.start();
        return server;
    }

    private SetuClient connect(TcpServer server) throws Exception {
        return SetuClient.connectPlain("127.0.0.1", server.localAddress().getPort(), 5000, 5000, 16 * 1024 * 1024);
    }
}
