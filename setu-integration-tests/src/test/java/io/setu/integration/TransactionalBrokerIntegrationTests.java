package io.setu.integration;

import io.setu.client.SetuClient;
import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.*;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.BrokerRequestHandler;
import io.setu.server.tcp.TcpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransactionalBrokerIntegrationTests {
    @TempDir Path directory;

    @Test void managedConsumptionTransactionsAndProducerRetriesCrossTheWireAndRestart() throws Exception {
        long generation;
        var identity = new ProducerIdentity("processor", 0, 0);
        try (var broker = new DefaultBroker(directory, 1024); var server = start(broker);
             var client = connect(server)) {
            assertEquals(ErrorCode.NONE, client.createTopic("input", 1).errorCode());
            assertEquals(ErrorCode.NONE, client.createTopic("output", 1).errorCode());
            var assignment = client.joinManagedGroup("group", "input", "member").assignment();
            assertNotNull(assignment);
            generation = assignment.generation();
            assertEquals(0, assignment.nextOffsets().get(0));
            assertEquals(ErrorCode.NONE, client.produceBatch("input", 0, List.of(new byte[]{7}),
                    AcknowledgementMode.DURABLE).errorCode());
            assertEquals(ErrorCode.STALE_GENERATION, client.fetch("group", "member", "input", 0, 0, 1024).errorCode());
            assertEquals(1, client.fetch("group", "member", "input", 0, 0, 1024, 0, generation).records().size());
            var commit = new ConsumerCommit("group", "member", "input", 0, 1, generation);
            assertEquals(0, client.transact("output", 0, List.of(new byte[]{8}), identity, commit).offset());
            assertEquals(0, client.transact("output", 0, List.of(new byte[]{8}), identity, commit).offset());
            assertEquals(1, client.groupAssignment("group", "input", "member").assignment().nextOffsets().get(0));
            assertEquals(1, client.fetch("output", 0, 0, 1024).records().size());
        }
        try (var broker = new DefaultBroker(directory, 1024); var server = start(broker);
             var client = connect(server)) {
            var commit = new ConsumerCommit("group", "member", "input", 0, 1, generation);
            assertEquals(0, client.transact("output", 0, List.of(new byte[]{8}), identity, commit).offset());
            assertEquals(1, client.produceIdempotent("output", 0, List.of(new byte[]{9}),
                    new ProducerIdentity("processor", 0, 1)).offset());
            assertEquals(2, client.fetch("output", 0, 0, 1024).records().size());
        }
    }

    @Test void acceptedModeReturnsNoOffsetAndLongPollObservesPublishedRecords() throws Exception {
        try (var broker = new DefaultBroker(directory, 1024); var server = start(broker);
             var client = connect(server)) {
            client.createTopic("orders", 1);
            var accepted = client.produceBatch("orders", 0, List.of(new byte[]{1}), AcknowledgementMode.ACCEPTED);
            assertEquals(ErrorCode.NONE, accepted.errorCode());
            assertEquals(-1, accepted.offset());
            assertEquals(1, client.fetch("orders", 0, 0, 1024, 2000).records().size());
        }
    }

    @Test void asyncProducerReconnectsAfterCommittedButLostReplyWithoutDuplicatingOutput() throws Exception {
        try (var broker = new DefaultBroker(directory, 1024);
             var listener = new java.net.ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress());
             var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            broker.execute(new BrokerRequest(1, Command.CREATE_TOPIC, "orders", -1, -1, -1, null, 1, null, null));
            var peer = workers.submit(() -> {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try (var socket = listener.accept()) {
                        var request = ProtocolCodec.decodeRequest(FrameCodec.readFrame(
                                new java.io.DataInputStream(socket.getInputStream()), 1024 * 1024));
                        var response = broker.execute(request);
                        if (attempt == 1) FrameCodec.writeFrame(new java.io.DataOutputStream(socket.getOutputStream()),
                                ProtocolCodec.encodeResponse(response), 1024 * 1024);
                    }
                }
                return true;
            });
            try (var client = new io.setu.client.AsyncSetuClient(
                    () -> SetuClient.connectPlain("127.0.0.1", listener.getLocalPort(), 2000, 2000, 1024 * 1024), 1, 8, 1)) {
                var response = client.produceIdempotent("orders", 0, List.of(new byte[]{1}),
                        new ProducerIdentity("retrying", 0, 0)).get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(ErrorCode.NONE, response.errorCode());
                assertEquals(0, response.offset());
                assertTrue(peer.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(1, broker.execute(new BrokerRequest(1, Command.FETCH, "orders", 0,
                        0, 1024, null, 0, null, null)).records().size());
            }
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
