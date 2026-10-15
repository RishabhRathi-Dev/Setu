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
