package io.setu.server;

import io.setu.core.protocol.FrameCodec;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.tcp.TcpServer;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class BufferedFramingTests {
    @Test
    void handlesFragmentedHeadersPipelinedFramesAndBodiesLargerThanTheStreamBuffer() throws Exception {
        try (TcpServer server = new TcpServer(new InetSocketAddress("127.0.0.1", 0),
                new FramedConnectionHandler(65536, request -> request))) {
            server.start();
            try (Socket socket = new Socket()) {
                socket.connect(server.localAddress(), 2000);
                socket.setSoTimeout(5000);
                byte[] first = new byte[10003];
                byte[] second = new byte[32768];
                Arrays.fill(first, (byte) 37);
                Arrays.fill(second, (byte) 73);
                ByteArrayOutputStream frames = new ByteArrayOutputStream();
                DataOutputStream encoded = new DataOutputStream(frames);
                FrameCodec.writeFrame(encoded, first, 65536);
                FrameCodec.writeFrame(encoded, second, 65536);
                byte[] wire = frames.toByteArray();
                OutputStream output = socket.getOutputStream();
                output.write(wire, 0, 2);
                output.flush();
                output.write(wire, 2, 29);
                output.flush();
                output.write(wire, 31, wire.length - 31);
                output.flush();
                socket.shutdownOutput();
                DataInputStream input = new DataInputStream(socket.getInputStream());
                assertArrayEquals(first, FrameCodec.readFrame(input, 65536));
                assertArrayEquals(second, FrameCodec.readFrame(input, 65536));
                assertNull(FrameCodec.readFrame(input, 65536));
            }
        }
    }
}
