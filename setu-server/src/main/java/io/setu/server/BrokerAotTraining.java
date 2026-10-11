package io.setu.server;

import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.*;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.BrokerRequestHandler;
import io.setu.server.tcp.TcpServer;
import io.setu.storage.log.StorageMode;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;

/** Finite standalone training workload; exercises the real plaintext protocol without production data. */
final class BrokerAotTraining {
    private static final int FRAME_BYTES = 16 * 1024 * 1024;
    private BrokerAotTraining() { }

    static void run() throws Exception {
        Path directory = Files.createTempDirectory("setu-aot-training-");
        try {
            for (StorageMode mode : StorageMode.values()) train(mode, directory.resolve(mode.name()));
        } finally {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static void train(StorageMode mode, Path directory) throws Exception {
        try (var broker = new DefaultBroker(mode, mode == StorageMode.FILE ? directory : null, 512);
             var server = new TcpServer(new InetSocketAddress("127.0.0.1", 0),
                     new FramedConnectionHandler(new BrokerRequestHandler(broker)))) {
            server.start();
            try (var socket = new Socket()) {
                socket.connect(server.localAddress(), 5_000);
                socket.setSoTimeout(10_000);
                socket.setTcpNoDelay(true);
                var input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                var output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                int id = 0;
                exchange(input, output, new BrokerRequest(id++, Command.CREATE_TOPIC, "aot-training", -1,
                        -1, -1, null, 4, null, null));
                exchange(input, output, new BrokerRequest(id++, Command.JOIN_GROUP, "aot-training", -1,
                        -1, -1, null, 0, "training", "reader"));
                long[] offsets = new long[4];
                var batch = Collections.nCopies(256, new byte[512]);
                for (int round = 0; round < 64; round++) {
                    int partition = round % offsets.length;
                    var produced = exchange(input, output, new BrokerRequest(id++, Command.PRODUCE_BATCH,
                            "aot-training", partition, -1, -1, null, 0, null, null, batch));
                    if (produced.offset() != offsets[partition]) throw new IOException("Training produce offset mismatch");
                    var fetched = exchange(input, output, new BrokerRequest(id++, Command.FETCH, "aot-training",
                            partition, offsets[partition], 1024 * 1024, null, 0, "training", "reader"));
                    if (fetched.records().size() != batch.size()) throw new IOException("Training fetch did not drain batch");
                    for (var record : fetched.records()) {
                        if (record.offset() != offsets[partition]++ || record.valueLength() != 512)
                            throw new IOException("Training fetch record mismatch");
                    }
                    exchange(input, output, new BrokerRequest(id++, Command.COMMIT_OFFSET, "aot-training",
                            partition, offsets[partition], 0, null, 0, "training", "reader"));
                }
                exchange(input, output, new BrokerRequest(id++, Command.METADATA, null, -1,
                        -1, -1, null, 0, null, null));
                exchange(input, output, new BrokerRequest(id, Command.LEAVE_GROUP, null, -1,
                        -1, -1, null, 0, "training", "reader"));
                System.out.printf("AOT_TRAINING mode=%s records=16384 partitions=4 completed=true%n", mode);
            }
        }
    }

    private static BrokerResponse exchange(DataInputStream input, DataOutputStream output, BrokerRequest request)
            throws IOException {
        FrameCodec.writeFrame(output, ProtocolCodec.encodeRequest(request), FRAME_BYTES);
        byte[] frame = FrameCodec.readFrame(input, FRAME_BYTES);
        if (frame == null) throw new EOFException("Training broker closed the connection");
        var response = ProtocolCodec.decodeResponse(frame);
        if (response.correlationId() != request.correlationId() || response.command() != request.command()
                || response.errorCode() != ErrorCode.NONE)
            throw new IOException("Training request failed: " + response);
        return response;
    }
}
