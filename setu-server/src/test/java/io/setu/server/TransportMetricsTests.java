package io.setu.server;

import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.tcp.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class TransportMetricsTests {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void reportsConnectionAdmissionAndReleasesBufferCredit(boolean select) throws Exception {
        var options = new TcpServerOptions(1, 16, 5000, 65536, 65536, 5000, 5000, 5000);
        var address = new InetSocketAddress("127.0.0.1", 0);
        var handler = new io.setu.server.dispatcher.BrokerRequestHandler(new io.setu.core.broker.Broker() {
            public io.setu.core.protocol.BrokerResponse execute(io.setu.core.protocol.BrokerRequest request) {
                return io.setu.core.protocol.BrokerResponse.ok(request.correlationId(), request.command());
            }
            public void close() { }
        });
        var blocking = select ? null : new TcpServer(address, new FramedConnectionHandler(handler), options);
        var selector = select ? new SelectorTcpServer(address, 1024, options, handler) : null;
        Supplier<Map<String, Long>> samples = () -> select ? selector.operationalGauges() : blocking.operationalGauges();
        try (AutoCloseable server = select ? selector : blocking) {
            if (select) selector.start(); else blocking.start();
            int port = (select ? selector.localAddress() : blocking.localAddress()).getPort();
            try (var first = new Socket("127.0.0.1", port)) {
                await(() -> samples.get().get("request_buffer_used_bytes") > 0);
                try (var excess = new Socket("127.0.0.1", port)) {
                    await(() -> samples.get().get("connections_limit_rejections_total") == 1);
                    assertEquals(1L, samples.get().get("connections_active"));
                    assertEquals(2L, samples.get().get("connections_accepted_total"));
                    assertEquals(65536L, samples.get().get("request_buffer_max_bytes"));
                }
            }
            await(() -> samples.get().get("connections_active") == 0 && samples.get().get("request_buffer_used_bytes") == 0);
            assertEquals(0L, samples.get().get("response_buffer_used_bytes"));
        }
    }

    private static void await(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            while (!condition.getAsBoolean()) Thread.sleep(10);
        });
    }
}
