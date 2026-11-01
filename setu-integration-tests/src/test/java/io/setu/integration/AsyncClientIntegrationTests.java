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
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AsyncClientIntegrationTests {
    private static SetuClient connect(int port) throws java.io.IOException {
        return SetuClient.connectPlain("127.0.0.1", port, 2000, 2000, 1024 * 1024);
    }

    @Test void typedBatchSnapshotsQueuedBytesAndListAndRejectsBeforeCopying() throws Exception {
        var connecting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var broker = new DefaultBroker(1024);
             var server = new TcpServer(new InetSocketAddress("127.0.0.1", 0),
                     new FramedConnectionHandler(1024 * 1024, new BrokerRequestHandler(broker)))) {
            server.start();
            int port = server.localAddress().getPort();
            try (var setup = connect(port)) {
                assertEquals(ErrorCode.NONE, setup.createTopic("batches", 1).errorCode());
                assertEquals(ErrorCode.NONE, setup.joinGroup("group", "batches", "member").errorCode());
            }
            try (var client = new AsyncSetuClient(() -> {
                connecting.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("gate timed out"); }
                catch (InterruptedException failure) { throw new java.io.IOException(failure); }
                return connect(port);
            }, 1, 1, 2)) {
                var running = client.request(new BrokerRequest(0, Command.METADATA, null, -1, -1, -1, null, 0, null, null));
                assertTrue(connecting.await(5, TimeUnit.SECONDS));
                byte[] first = {1}, second = {2};
                var values = new ArrayList<>(List.of(first, second));
                var queued = client.produceBatch("batches", 0, values);
                first[0] = 99; second[0] = 98; values.clear();
                var inaccessible = new java.util.AbstractList<byte[]>() {
                    @Override public byte[] get(int index) { throw new AssertionError("Rejected batch was inspected"); }
                    @Override public int size() { throw new AssertionError("Rejected batch was inspected"); }
                };
                var rejected = client.produceBatch("batches", 0, inaccessible);
                assertInstanceOf(RejectedExecutionException.class,
                        assertThrows(ExecutionException.class, () -> rejected.get(1, TimeUnit.SECONDS)).getCause());
                release.countDown();
                assertEquals(ErrorCode.NONE, running.get(5, TimeUnit.SECONDS).errorCode());
                var appended = queued.get(5, TimeUnit.SECONDS);
                assertEquals(Command.PRODUCE_BATCH, appended.command());
                assertEquals(ErrorCode.NONE, appended.errorCode());
                assertEquals(0, appended.offset());
                try (var reader = connect(port)) {
                    var records = reader.fetch("batches", 0, 0, 1024).records();
                    assertEquals(2, records.size());
                    assertArrayEquals(new byte[]{1}, records.getFirst().value());
                    assertArrayEquals(new byte[]{2}, records.getLast().value());
                }
                // A failed snapshot returns its admission slot; local encoder validation also leaves the pool usable.
                assertThrows(NullPointerException.class, () -> client.produceBatch("batches", 0, null));
                assertThrows(ExecutionException.class,
                        () -> client.produceBatch("batches", 0, List.of()).get(5, TimeUnit.SECONDS));
                assertEquals(ErrorCode.NONE,
                        client.produceBatch("batches", 0, List.of(new byte[]{3})).get(5, TimeUnit.SECONDS).errorCode());
            } finally { release.countDown(); }
        }
    }

    @Test void longPollLeavesOtherConnectionAvailable() throws Exception {
        var fetching = new CountDownLatch(1);
        try (var broker = new DefaultBroker(1024);
             var server = new TcpServer(new InetSocketAddress("127.0.0.1", 0),
                     new FramedConnectionHandler(1024 * 1024, bytes -> {
                         if (ProtocolCodec.decodeRequest(bytes).command() == Command.FETCH) fetching.countDown();
                         return new BrokerRequestHandler(broker).handle(bytes);
                     }))) {
            server.start();
            int port = server.localAddress().getPort();
            try (var setup = connect(port)) {
                assertEquals(ErrorCode.NONE, setup.createTopic("poll", 1).errorCode());
                assertEquals(ErrorCode.NONE, setup.joinGroup("g", "poll", "m").errorCode());
            }
            try (var client = new AsyncSetuClient(() -> connect(port), 2, 2, 0)) {
                var poll = client.request(new BrokerRequest(0, Command.FETCH, "poll", 0, 0, 1024,
                        null, 0, null, null).withWaitMillis(1500));
                assertTrue(fetching.await(5, TimeUnit.SECONDS));
                assertEquals(ErrorCode.NONE,
                        client.produceBatch("poll", 0, List.of(new byte[]{7})).get(1, TimeUnit.SECONDS).errorCode());
                var response = poll.get(5, TimeUnit.SECONDS);
                assertEquals(ErrorCode.NONE, response.errorCode());
                assertArrayEquals(new byte[]{7}, response.records().getFirst().value());
            }
        }
    }

    @Test void slowResponseBoundsQueueAndCancelledBatchDoesNotReachBroker() throws Exception {
        var receiving = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var broker = new DefaultBroker(1024);
             var server = new TcpServer(new InetSocketAddress("127.0.0.1", 0),
                     new FramedConnectionHandler(1024 * 1024, bytes -> {
                         if (ProtocolCodec.decodeRequest(bytes).command() == Command.METADATA) {
                             receiving.countDown();
                             try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("gate timed out"); }
                             catch (InterruptedException error) { throw new java.io.IOException(error); }
                         }
                         return new BrokerRequestHandler(broker).handle(bytes);
                     }))) {
            server.start();
            int port = server.localAddress().getPort();
            try (var setup = connect(port)) {
                assertEquals(ErrorCode.NONE, setup.createTopic("cancel", 1).errorCode());
            }
            try (var client = new AsyncSetuClient(() -> connect(port), 1, 2, 0)) {
                var metadata = new BrokerRequest(0, Command.METADATA, null, -1, -1, -1, null, 0, null, null);
                var running = client.request(metadata);
                assertTrue(receiving.await(5, TimeUnit.SECONDS));
                var cancelled = client.produceBatch("cancel", 0, List.of(new byte[]{1}));
                assertTrue(cancelled.cancel(false));
                var barrier = client.request(metadata);
                assertInstanceOf(RejectedExecutionException.class, assertThrows(ExecutionException.class,
                        () -> client.request(metadata).get(1, TimeUnit.SECONDS)).getCause());
                release.countDown();
                assertEquals(ErrorCode.NONE, running.get(5, TimeUnit.SECONDS).errorCode());
                assertEquals(ErrorCode.NONE, barrier.get(5, TimeUnit.SECONDS).errorCode());
                assertEquals(0, client.produceBatch("cancel", 0, List.of(new byte[]{2}))
                        .get(5, TimeUnit.SECONDS).offset());
            } finally { release.countDown(); }
        }
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
        for (Command command : new Command[]{Command.METADATA, Command.PRODUCE, Command.PRODUCE_BATCH}) {
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
                    var response = command == Command.PRODUCE_BATCH
                            ? client.produceBatch("orders", 0, List.of(new byte[]{1})) : client.request(request);
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
