package io.setu.server;

import io.setu.core.protocol.FrameCodec;
import io.setu.server.dispatcher.RequestHandler;
import io.setu.server.tcp.SelectorTcpServer;
import io.setu.server.tcp.TcpServerOptions;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SelectorTcpServerTests {
    @Test
    void multiplexesMultipleFramesAndConnections() throws Exception {
        RequestHandler echo = request -> request;
        try (SelectorTcpServer server = new SelectorTcpServer(
                new InetSocketAddress("127.0.0.1", 0), 1024,
                new TcpServerOptions(8, 16, 5_000), echo)) {
            server.start();
            try (Socket first = connect(server.localAddress());
                 Socket second = connect(server.localAddress())) {
                DataInputStream firstIn = new DataInputStream(first.getInputStream());
                DataOutputStream firstOut = new DataOutputStream(first.getOutputStream());
                DataInputStream secondIn = new DataInputStream(second.getInputStream());
                DataOutputStream secondOut = new DataOutputStream(second.getOutputStream());
                byte[] a = { 1, 2, 3 };
                byte[] b = { 4, 5 };
                FrameCodec.writeFrame(firstOut, a, 1024);
                FrameCodec.writeFrame(firstOut, b, 1024);
                FrameCodec.writeFrame(secondOut, b, 1024);
                assertArrayEquals(a, FrameCodec.readFrame(firstIn, 1024));
                assertArrayEquals(b, FrameCodec.readFrame(firstIn, 1024));
                assertArrayEquals(b, FrameCodec.readFrame(secondIn, 1024));
            }
        }
    }

    @Test
    void appliesConnectionLimitAndIdleTimeout() throws Exception {
        RequestHandler echo = request -> request;
        try (SelectorTcpServer server = new SelectorTcpServer(
                new InetSocketAddress("127.0.0.1", 0), 1024,
                new TcpServerOptions(1, 16, 350), echo)) {
            server.start();
            try (Socket first = connect(server.localAddress())) {
                DataInputStream input = new DataInputStream(first.getInputStream());
                DataOutputStream output = new DataOutputStream(first.getOutputStream());
                FrameCodec.writeFrame(output, new byte[] { 7 }, 1024);
                assertArrayEquals(new byte[] { 7 }, FrameCodec.readFrame(input, 1024));

                try (Socket excess = connect(server.localAddress())) {
                    assertEquals(-1, excess.getInputStream().read());
                }

                first.setSoTimeout(3_000);
                assertEquals(-1, input.read());
            }
        }
    }

    private static Socket connect(InetSocketAddress address) throws Exception {
        Socket socket = new Socket();
        socket.connect(address, 2_000);
        socket.setSoTimeout(5_000);
        return socket;
    }
}
