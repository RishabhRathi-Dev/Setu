package io.setu.integration;

import io.setu.client.AsyncSetuClient;
import io.setu.client.SetuClient;
import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.*;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.BrokerRequestHandler;
import io.setu.server.tcp.TcpServer;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AsyncClientIntegrationTests {
    private static SetuClient connect(int port) throws java.io.IOException {
        return SetuClient.connectPlain("127.0.0.1", port, 2000, 2000, 1024 * 1024);
    }

    @Test void poolRoutesKeysAndCopiesPayloadBeforeScheduling() throws Exception {
        try (var broker = new DefaultBroker(1024);
             var server = new TcpServer(new InetSocketAddress("127.0.0.1", 0),
                     new FramedConnectionHandler(1024 * 1024, new BrokerRequestHandler(broker)))) {
            server.start();
            try (var setup = connect(server.localAddress().getPort())) {
                assertEquals(ErrorCode.NONE, setup.createTopic("orders", 4).errorCode());
                assertEquals(ErrorCode.NONE, setup.joinGroup("group", "orders", "member").errorCode());
            }
            try (var client = new AsyncSetuClient(() -> connect(server.localAddress().getPort()), 4, 128, 1)) {
                var writes = new ArrayList<CompletableFuture<BrokerResponse>>();
                byte[] value = {7};
                for (int index = 0; index < 32; index++) writes.add(client.produce("orders", "key", value));
                value[0] = 99;
                for (var write : writes) assertEquals(ErrorCode.NONE, write.get(5, TimeUnit.SECONDS).errorCode());
                try (var reader = connect(server.localAddress().getPort())) {
                    var records = reader.fetch("orders", Math.floorMod("key".hashCode(), 4), 0, 1024).records();
                    assertEquals(32, records.size());
                    records.forEach(record -> assertArrayEquals(new byte[]{7}, record.value()));
                }
            }
        }
    }

    @Test void readReconnectsButUncertainProduceIsNeverRetried() throws Exception {
        for (Command command : new Command[]{Command.METADATA, Command.PRODUCE}) {
            try (var listener = new ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress());
                 var worker = Executors.newVirtualThreadPerTaskExecutor()) {
                var opened = new AtomicInteger();
                var peer = worker.submit(() -> {
                    try (var socket = listener.accept()) {
                        FrameCodec.readFrame(new DataInputStream(socket.getInputStream()), 1024 * 1024);
                    }
                    if (command == Command.METADATA) {
                        try (var socket = listener.accept()) {
                            var request = ProtocolCodec.decodeRequest(FrameCodec.readFrame(
                                    new DataInputStream(socket.getInputStream()), 1024 * 1024));
                            FrameCodec.writeFrame(new DataOutputStream(socket.getOutputStream()),
                                    ProtocolCodec.encodeResponse(BrokerResponse.ok(request.correlationId(), request.command())),
                                    1024 * 1024);
                        }
                    }
                    return true;
                });
                try (var client = new AsyncSetuClient(() -> {
                    opened.incrementAndGet();
                    return connect(listener.getLocalPort());
                }, 1, 8, 2)) {
                    var request = new BrokerRequest(1, command, "orders", 0, 0, 1024, new byte[]{1}, 0, null, null);
                    var response = client.request(request);
                    if (command == Command.METADATA) {
                        assertEquals(ErrorCode.NONE, response.get(5, TimeUnit.SECONDS).errorCode());
                        assertEquals(2, opened.get());
                    } else {
                        assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS));
                        assertEquals(1, opened.get());
                    }
                    assertTrue(peer.get(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test void closeCompletesRunningAndQueuedRequestsAndRejectsNewWork() throws Exception {
        var connected = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var client = new AsyncSetuClient(() -> {
            connected.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { throw new java.io.IOException(interrupted); }
            throw new java.io.IOException("test connection unavailable");
        }, 1, 1, 0);
        var request = new BrokerRequest(0, Command.METADATA, null, -1, -1, -1, null, 0, null, null);
        try {
            var running = client.request(request);
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            var queued = client.request(request);
            assertTrue(client.request(request).isCompletedExceptionally());
            client.close();
            assertTrue(running.isCompletedExceptionally());
            assertTrue(queued.isCompletedExceptionally());
            assertTrue(client.request(request).isCompletedExceptionally());
        } finally {
            release.countDown();
            client.close();
        }
    }
}
