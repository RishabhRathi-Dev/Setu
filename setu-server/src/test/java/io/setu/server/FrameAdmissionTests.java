package io.setu.server;

import io.setu.core.protocol.FrameCodec;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.tcp.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import static org.junit.jupiter.api.Assertions.*;

class FrameAdmissionTests {
    @Test void frameDeadlineEndsBeforeDispatchAndDoesNotCloseTheNextFrame() throws Exception {
        var options = new TcpServerOptions(4, 4, 0, 128 * 1024, 128 * 1024, 3000, 3000, 100);
        var handler = new FramedConnectionHandler(1024, bytes -> {
            try { Thread.sleep(200); } catch (InterruptedException e) { throw new IOException(e); }
            return bytes;
        });
        try (var server = new TcpServer(new InetSocketAddress("127.0.0.1", 0), handler, options)) {
            server.start();
            try (var client = new Socket()) {
                client.connect(server.localAddress()); client.setSoTimeout(3000);
                for (int i = 0; i < 2; i++) {
                    FrameCodec.writeFrame(new DataOutputStream(client.getOutputStream()), new byte[]{1}, 1024);
                    assertArrayEquals(new byte[]{1}, FrameCodec.readFrame(new DataInputStream(client.getInputStream()), 1024));
                }
            }
        }
    }

    @Test void blockingFrameDeadlineReleasesStalledBodies() throws Exception { verifyReadDeadline(false, false, false); }
    @Test void selectorFrameDeadlineReleasesStalledBodies() throws Exception { verifyReadDeadline(true, false, false); }
    @Test void blockingFrameDeadlineStopsTrickledBodies() throws Exception { verifyReadDeadline(false, true, false); }
    @Test void selectorFrameDeadlineStopsTrickledBodies() throws Exception { verifyReadDeadline(true, true, false); }
    @Test void blockingFrameDeadlineCoversPartialHeaders() throws Exception { verifyReadDeadline(false, true, true); }
    @Test void selectorFrameDeadlineCoversPartialHeaders() throws Exception { verifyReadDeadline(true, true, true); }

    private void verifyReadDeadline(boolean selector, boolean trickle, boolean header) throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        io.setu.server.dispatcher.RequestHandler handler = bytes -> { calls.incrementAndGet(); return bytes; };
        var options = new TcpServerOptions(4, 4, 0, 128 * 1024, 128 * 1024, 3000, 3000, 250);
        var framed = new FramedConnectionHandler(1024, handler);
        var blocking = selector ? null : new TcpServer(new InetSocketAddress("127.0.0.1", 0), framed, options);
        var nio = selector ? new SelectorTcpServer(new InetSocketAddress("127.0.0.1", 0), 1024, options, handler) : null;
        LongSupplier requests = selector ? nio::inFlightRequestBytes : framed::inFlightRequestBytes;
        try (AutoCloseable server = selector ? nio : blocking; var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            if (selector) nio.start(); else blocking.start();
            var address = selector ? nio.localAddress() : blocking.localAddress();
            try (var partial = new Socket()) {
                partial.connect(address);
                partial.setSoTimeout(3000);
                var output = new DataOutputStream(partial.getOutputStream());
                if (header) output.writeByte(0); else { output.writeInt(1024); output.writeByte(1); }
                output.flush();
                var writer = workers.submit(() -> {
                    if (!trickle) return null;
                    try {
                        for (int i = 0; i < (header ? 2 : 100); i++) {
                            Thread.sleep(75);
                            output.writeByte(0); output.flush();
                        }
                    } catch (IOException closed) { /* Expiry closes the socket while bytes still trickle. */ }
                    return null;
                });
                try { assertEquals(-1, partial.getInputStream().read()); }
                catch (SocketException reset) { /* EOF and reset both mean the deadline closed the peer. */ }
                writer.cancel(true);
            }
            awaitRequestBytes(requests, 0);
            assertEquals(0, calls.get());
            try (var healthy = new Socket()) {
                healthy.connect(address); healthy.setSoTimeout(3000);
                FrameCodec.writeFrame(new DataOutputStream(healthy.getOutputStream()), new byte[]{1}, 1024);
                assertArrayEquals(new byte[]{1}, FrameCodec.readFrame(new DataInputStream(healthy.getInputStream()), 1024));
            }
            assertEquals(1, calls.get());
        }
        assertEquals(0, requests.getAsLong());
    }

    @Test void blockingWriteDeadlineReleasesSlowReaders() throws Exception { verifyWriteTimeout(false); }
    @Test void selectorWriteDeadlineReleasesSlowReaders() throws Exception { verifyWriteTimeout(true); }

    private void verifyWriteTimeout(boolean selector) throws Exception {
        final int size = 16 * 1024 * 1024;
        io.setu.server.dispatcher.RequestHandler handler = request -> {
            io.setu.core.protocol.ResponseBuffers.reserve(size);
            return new byte[size];
        };
        var options = new TcpServerOptions(4, 4, 0, 1024 * 1024, 32 * 1024 * 1024, 150);
        var framed = new FramedConnectionHandler(size, handler);
        var blocking = selector ? null : new TcpServer(new InetSocketAddress("127.0.0.1", 0), framed, options);
        var nio = selector ? new SelectorTcpServer(new InetSocketAddress("127.0.0.1", 0), size, options, handler) : null;
        LongSupplier responseBytes = selector ? nio::inFlightResponseBytes : framed::inFlightResponseBytes;
        try (AutoCloseable server = selector ? nio : blocking; var reader = new Socket()) {
            if (selector) nio.start(); else blocking.start();
            reader.setReceiveBufferSize(1024);
            reader.connect(selector ? nio.localAddress() : blocking.localAddress());
            FrameCodec.writeFrame(new DataOutputStream(reader.getOutputStream()), new byte[8], size);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (responseBytes.getAsLong() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(responseBytes.getAsLong() > 0);
            // The peer never reads; a full socket must not pin the shared response budget forever.
            while (responseBytes.getAsLong() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0, responseBytes.getAsLong());
        }
    }
    @Test void blockingTransportRejectsBeforeReadingBodyAndReleasesOnDisconnect() throws Exception { verify(false); }
    @Test void selectorTransportRejectsBeforeReadingBodyAndReleasesOnDisconnect() throws Exception { verify(true); }
    private void verify(boolean selector) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        io.setu.server.dispatcher.RequestHandler handler = bytes -> {
            entered.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("timeout"); }
            catch (InterruptedException e) { throw new IOException(e); }
            return bytes;
        };
        long base = selector ? 1024 : 17 * 1024;
        // Admit both connections, but leave too little capacity for the second frame.
        // No server read timeout: a missing admission check must fail the client read timeout.
        var options = new TcpServerOptions(10, 10, 0, 2 * base + 1100, 2048);
        var framed = new FramedConnectionHandler(1024, handler);
        var blocking = selector ? null : new TcpServer(new InetSocketAddress("127.0.0.1", 0), framed, options);
        var nio = selector ? new SelectorTcpServer(new InetSocketAddress("127.0.0.1", 0), 1024, options, handler) : null;
        AutoCloseable server = selector ? nio : blocking;
        LongSupplier requests = selector ? nio::inFlightRequestBytes : framed::inFlightRequestBytes;
        LongSupplier responses = selector ? nio::inFlightResponseBytes : framed::inFlightResponseBytes;
        try (server) {
            if (selector) nio.start(); else blocking.start();
            int port = (selector ? nio.localAddress() : blocking.localAddress()).getPort();
            try (var first = new Socket("127.0.0.1", port)) {
                first.setSoTimeout(3000);
                FrameCodec.writeFrame(new DataOutputStream(first.getOutputStream()), new byte[8], 1024);
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                // The legacy byte[] handler also reserves its eight-byte compatibility copy.
                assertEquals(base + 1048, requests.getAsLong());
                try (var rejected = new Socket("127.0.0.1", port)) {
                    rejected.setSoTimeout(3000);
                    assertRejectedBeforeBody(rejected);
                    awaitRequestBytes(requests, base + 1048);
                }
                release.countDown();
                assertEquals(8, FrameCodec.readFrame(new DataInputStream(first.getInputStream()), 1024).length);
            } finally { release.countDown(); }
            awaitRequestBytes(requests, 0);
            // A partially received frame must release its credit even without a completed request.
            try (var partial = new Socket("127.0.0.1", port)) {
                var output = new DataOutputStream(partial.getOutputStream());
                output.writeInt(8); output.writeByte(1); output.flush();
                awaitRequestBytes(requests, base + 1040);
            }
        }
        assertEquals(0, requests.getAsLong());
        assertEquals(0, responses.getAsLong());
    }

    private void assertRejectedBeforeBody(Socket rejected) throws IOException {
        try {
            var header = new DataOutputStream(rejected.getOutputStream());
            header.writeInt(8); header.flush(); // no body sent: admission must already reject
            assertEquals(-1, rejected.getInputStream().read());
        } catch (SocketException closedByPeer) {
            // TCP rejection can surface as EOF or a reset, depending on the OS and timing.
            // SocketTimeoutException is not caught: waiting for the body would fail the test.
        }
    }

    private void awaitRequestBytes(LongSupplier requests, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (requests.getAsLong() != expected && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(expected, requests.getAsLong());
    }
}
