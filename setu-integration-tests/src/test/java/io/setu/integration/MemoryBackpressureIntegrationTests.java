package io.setu.integration;

import io.setu.client.SetuClient;
import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.ErrorCode;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.BrokerRequestHandler;
import io.setu.server.tcp.SelectorTcpServer;
import io.setu.server.tcp.TcpServer;
import io.setu.server.tcp.TcpServerOptions;
import io.setu.storage.log.StorageMode;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class MemoryBackpressureIntegrationTests {
    @Test void blockingTransportResumesAfterAcknowledgement() throws Exception { verify(false); }
    @Test void selectorTransportResumesAfterAcknowledgement() throws Exception { verify(true); }

    private void verify(boolean useSelector) throws Exception {
        try (var broker = new DefaultBroker(StorageMode.IN_MEMORY, null, 512, 1024, 5000, 10000, 32 * 1024)) {
            var handler = new BrokerRequestHandler(broker);
            var address = new InetSocketAddress("127.0.0.1", 0);
            try (TcpServer blocking = useSelector ? null : new TcpServer(address, new FramedConnectionHandler(handler));
                 SelectorTcpServer selector = useSelector ? new SelectorTcpServer(address, 16 * 1024 * 1024,
                         TcpServerOptions.defaults(), (identity, request) -> handler.handle(request)) : null) {
                if (blocking != null) blocking.start(); else selector.start();
                int port = blocking != null ? blocking.localAddress().getPort() : selector.localAddress().getPort();
                try (var producer = client(port); var consumer = client(port)) {
                    assertEquals(ErrorCode.NONE, producer.createTopic("orders", 1).errorCode());
                    assertEquals(ErrorCode.NONE, consumer.joinGroup("g", "orders", "reader").errorCode());
                    var batch = Collections.nCopies(32, new byte[512]);
                    assertEquals(0, producer.produceBatch("orders", 0, batch).offset());
                    long retained = broker.retainedInMemoryBytes();
                    var rejected = producer.produceBatch("orders", 0, batch);
                    assertEquals(ErrorCode.QUOTA_EXCEEDED, rejected.errorCode());
                    assertTrue(rejected.errorMessage().contains("In-memory log budget exceeded"));
                    assertEquals(retained, broker.retainedInMemoryBytes());
                    assertEquals(ErrorCode.NONE, producer.metadata().errorCode(), "connection stays usable while full");
                    var fetched = consumer.fetch("g", "reader", "orders", 0, 0, 32 * 512);
                    assertEquals(32, fetched.records().size());
                    assertEquals(ErrorCode.NONE, consumer.acknowledge("g", "reader", "orders", 0, 32).errorCode());
                    assertEquals(0, broker.retainedInMemoryBytes());
                    var retried = producer.produceBatch("orders", 0, batch);
                    assertEquals(ErrorCode.NONE, retried.errorCode());
                    assertEquals(32, retried.offset());
                    var remaining = consumer.fetch("g", "reader", "orders", 0, 32, 32 * 512).records();
                    assertEquals(32, remaining.size());
                    assertEquals(63, remaining.getLast().offset());
                }
            }
        }
    }

    private static SetuClient client(int port) throws java.io.IOException {
        return SetuClient.connectPlain("127.0.0.1", port, 2000, 5000, 16 * 1024 * 1024);
    }
}
