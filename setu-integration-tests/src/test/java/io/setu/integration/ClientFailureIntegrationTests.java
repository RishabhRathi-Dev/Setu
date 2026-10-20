package io.setu.integration;

import io.setu.client.SetuClient;
import io.setu.core.protocol.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientFailureIntegrationTests {
    @Test void closeCancelsAnIndefiniteResponseRead() throws Exception { verifyCancellation(false); }
    @Test void closeCancelsABlockedRequestWrite() throws Exception { verifyCancellation(true); }

    private void verifyCancellation(boolean write) throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            listener.setReceiveBufferSize(1024);
            try (var client = SetuClient.connectPlain("localhost", listener.getLocalPort(), 1000, 0, 16 * 1024 * 1024);
                 var peer = listener.accept()) {
                peer.setReceiveBufferSize(1024);
                var request = workers.submit(() -> {
                    assertThrows(IOException.class, () -> {
                        if (write) client.produceBatch("orders", 0, List.of(new byte[10 * 1024 * 1024]));
                        else client.metadata();
                    });
                });
                peer.setSoTimeout(3000);
                var input = new DataInputStream(peer.getInputStream());
                assertTrue(input.readInt() > 0); // Exchange owns the serialization monitor now.
                var close = workers.submit(() -> { client.close(); return null; });
                close.get(3, TimeUnit.SECONDS);
                request.get(3, TimeUnit.SECONDS);
                assertThrows(IOException.class, client::metadata);
            }
        }
    }

    @Test void failedExchangesPermanentlyInvalidateTheConnection() throws Exception {
        for (String failure : List.of("eof", "partial", "length", "correlation", "command", "malformed", "timeout")) {
            try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                 var workers = Executors.newVirtualThreadPerTaskExecutor();
                 var client = SetuClient.connectPlain("localhost", listener.getLocalPort(), 1000, 150, 1024);
                 var peer = listener.accept()) {
                var serving = workers.submit(() -> {
                    peer.setSoTimeout(3000);
                    var input = new DataInputStream(peer.getInputStream());
                    var request = ProtocolCodec.decodeRequest(FrameCodec.readFrame(input, 1024));
                    var output = new DataOutputStream(peer.getOutputStream());
                    switch (failure) {
                        case "eof" -> peer.shutdownOutput();
                        case "partial" -> { output.writeInt(40); output.writeByte(1); output.flush(); }
                        case "length" -> { output.writeInt(-1); output.flush(); }
                        case "malformed" -> FrameCodec.writeFrame(output, new byte[12], 1024);
                        case "correlation", "command" -> FrameCodec.writeFrame(output,
                                ProtocolCodec.encodeResponse(BrokerResponse.ok(
                                        request.correlationId() + (failure.equals("correlation") ? 1 : 0),
                                        failure.equals("command") ? Command.HEARTBEAT : Command.METADATA)), 1024);
                        case "timeout" -> { /* Wait for the client to close after its read timeout. */ }
                    }
                    assertEquals(-1, input.read(), "Failed connection must close without sending another request");
                    return null;
                });
                assertThrows(IOException.class, client::metadata, failure);
                var closed = assertThrows(IOException.class, client::metadata, failure);
                assertTrue(closed.getMessage().contains("closed"), failure);
                serving.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test void brokerErrorResponsesKeepTheConnectionReusable() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var workers = Executors.newVirtualThreadPerTaskExecutor();
             var client = SetuClient.connectPlain("localhost", listener.getLocalPort(), 1000, 3000, 1024);
             var peer = listener.accept()) {
            var serving = workers.submit(() -> {
                var input = new DataInputStream(peer.getInputStream());
                var output = new DataOutputStream(peer.getOutputStream());
                for (var error : List.of(ErrorCode.AUTHORIZATION_DENIED, ErrorCode.NONE)) {
                    var request = ProtocolCodec.decodeRequest(FrameCodec.readFrame(input, 1024));
                    FrameCodec.writeFrame(output, ProtocolCodec.encodeResponse(new BrokerResponse(
                            request.correlationId(), request.command(), error, "", -1, List.of(), Map.of())), 1024);
                }
                return null;
            });
            assertEquals(ErrorCode.AUTHORIZATION_DENIED, client.metadata().errorCode());
            assertEquals(ErrorCode.NONE, client.metadata().errorCode());
            serving.get(3, TimeUnit.SECONDS);
        }
    }
}
