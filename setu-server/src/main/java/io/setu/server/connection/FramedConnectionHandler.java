package io.setu.server.connection;

import io.setu.server.codec.FrameCodec;
import io.setu.server.dispatcher.RequestHandler;
import io.setu.server.dispatcher.RequestProcessor;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.Objects;

/** Applies length-prefixed framing and delegates complete requests to the dispatcher. */
public final class FramedConnectionHandler implements ConnectionHandler {
    private final int maxFrameSize;
    private final ConnectionSessionFactory sessionFactory;
    private final RequestProcessor requestProcessor;

    public FramedConnectionHandler(int maxFrameSize, ConnectionSessionFactory sessionFactory,
                                   RequestProcessor requestProcessor) {
        if (maxFrameSize < 0) {
            throw new IllegalArgumentException("maxFrameSize must be non-negative");
        }
        this.maxFrameSize = maxFrameSize;
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.requestProcessor = Objects.requireNonNull(requestProcessor, "requestProcessor");
    }

    public FramedConnectionHandler(int maxFrameSize, RequestHandler requestHandler) {
        this(maxFrameSize,
                new PlaintextConnectionSessionFactory(),
                plaintextProcessor(requestHandler));
    }

    public FramedConnectionHandler(RequestHandler requestHandler) {
        this(FrameCodec.DEFAULT_MAX_FRAME_SIZE, requestHandler);
    }

    private static RequestProcessor plaintextProcessor(RequestHandler requestHandler) {
        Objects.requireNonNull(requestHandler, "requestHandler");
        return (identity, request) -> requestHandler.handle(request);
    }

    @Override
    public void handle(SocketChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        try (ConnectionSession session = sessionFactory.open(channel)) {
            DataInputStream input = new DataInputStream(session.input());
            DataOutputStream output = new DataOutputStream(session.output());
            while (channel.isOpen()) {
                byte[] request = FrameCodec.readFrame(input, maxFrameSize);
                if (request == null) {
                    return;
                }
                byte[] response = Objects.requireNonNull(
                        requestProcessor.process(session.identity(), request), "request processor response");
                FrameCodec.writeFrame(output, response, maxFrameSize);
            }
        }
    }
}
