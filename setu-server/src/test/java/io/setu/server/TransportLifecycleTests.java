package io.setu.server;

import io.setu.core.protocol.FrameCodec;
import io.setu.core.protocol.ResponseBuffers;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.RequestHandler;
import io.setu.server.tcp.*;
import org.junit.jupiter.api.Test;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class TransportLifecycleTests {
    @Test void repeatedPartialFrameDisconnectsReturnAllCreditInBothTransports() throws Exception {
        for (boolean selector : new boolean[]{false, true}) {
            try (var server = new Harness(selector, bytes -> { fail("incomplete frame dispatched"); return bytes; })) {
                for (int repeat = 0; repeat < 20; repeat++) {
                    try (var socket = server.connect()) {
                        var output = new DataOutputStream(socket.getOutputStream());
                        output.writeInt(8192); output.writeByte(1); output.flush();
                        await(() -> server.requestBytes() > 17000);
                    }
                    await(server::released);
                }
            }
        }
    }

    @Test void repeatedDispatchFailuresReleaseMaterializedResponseCredit() throws Exception {
        for (boolean selector : new boolean[]{false, true}) {
            try (var server = new Harness(selector, bytes -> {
                ResponseBuffers.reserve(4096);
                throw new IOException("injected after response reservation");
            })) {
                for (int repeat = 0; repeat < 10; repeat++) {
                    try (var socket = server.connect()) {
                        FrameCodec.writeFrame(new DataOutputStream(socket.getOutputStream()), new byte[]{1}, 8192);
                        try { assertEquals(-1, socket.getInputStream().read()); }
                        catch (SocketException reset) { /* A reset and EOF both terminate the failed exchange. */ }
                    }
                    await(server::released);
                }
            }
        }
    }

    @Test void shutdownInterruptsActiveDispatchAndReleasesBothBudgets() throws Exception {
        for (boolean selector : new boolean[]{false, true}) {
            for (int repeat = 0; repeat < 3; repeat++) {
                var entered = new CountDownLatch(1);
                var interrupted = new CountDownLatch(1);
                try (var server = new Harness(selector, bytes -> {
                    ResponseBuffers.reserve(4096);
                    entered.countDown();
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException failure) { interrupted.countDown(); throw new IOException(failure); }
                    return bytes;
                }); var socket = server.connect()) {
                    FrameCodec.writeFrame(new DataOutputStream(socket.getOutputStream()), new byte[]{1}, 8192);
                    assertTrue(entered.await(3, TimeUnit.SECONDS));
                    assertTrue(server.responseBytes() >= 4096);
                    server.close();
                    assertTrue(interrupted.await(3, TimeUnit.SECONDS));
                    assertTrue(server.released());
                }
            }
        }
    }

    private static void await(BooleanSupplier predicate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!predicate.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
        assertTrue(predicate.getAsBoolean(), "transport did not return to the expected credit state");
    }

    private static final class Harness implements AutoCloseable {
        final FramedConnectionHandler framed;
        final TcpServer blocking;
        final SelectorTcpServer selector;

        Harness(boolean useSelector, RequestHandler handler) throws Exception {
            var options = new TcpServerOptions(4, 4, 0, 256 * 1024, 8192);
            var address = new InetSocketAddress("127.0.0.1", 0);
            framed = new FramedConnectionHandler(8192, handler);
            blocking = useSelector ? null : new TcpServer(address, framed, options);
            selector = useSelector ? new SelectorTcpServer(address, 8192, options, handler) : null;
            if (selector != null) selector.start(); else blocking.start();
        }
        Socket connect() throws IOException {
            var socket = new Socket();
            socket.connect(selector == null ? blocking.localAddress() : selector.localAddress());
            socket.setSoTimeout(3000);
            return socket;
        }
        long requestBytes() { return selector == null ? framed.inFlightRequestBytes() : selector.inFlightRequestBytes(); }
        long responseBytes() { return selector == null ? framed.inFlightResponseBytes() : selector.inFlightResponseBytes(); }
        boolean released() {
            var metrics = selector == null ? blocking.operationalGauges() : selector.operationalGauges();
            return requestBytes() == 0 && responseBytes() == 0 && metrics.get("connections_active") == 0;
        }
        public void close() throws IOException { if (selector == null) blocking.close(); else selector.close(); }
    }
}
