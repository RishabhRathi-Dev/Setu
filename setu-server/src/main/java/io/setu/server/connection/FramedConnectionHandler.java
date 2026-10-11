package io.setu.server.connection;

import io.setu.server.codec.FrameCodec;
import io.setu.server.dispatcher.RequestHandler;
import io.setu.server.dispatcher.RequestProcessor;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Applies length-prefixed framing and delegates complete requests to the dispatcher. */
public final class FramedConnectionHandler implements ConnectionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(FramedConnectionHandler.class);
    private static final AtomicLong SESSION_IDS = new AtomicLong();
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
        var peer = channel.getRemoteAddress();
        long sessionId = SESSION_IDS.incrementAndGet();
        long frames = 0;
        long started = System.nanoTime();
        try (var sessionContext = MDC.putCloseable("sessionId", Long.toString(sessionId));
             ConnectionSession session = sessionFactory.open(channel);
             var principalContext = MDC.putCloseable("principal", session.identity() == null
                     ? "anonymous" : session.identity().principal().toString())) {
            if (LOG.isDebugEnabled()) LOG.debug("Session opened peer={} max_frame_bytes={} tcp_nodelay={}", peer, maxFrameSize,
                    channel.socket().getTcpNoDelay());
            DataInputStream input = new DataInputStream(new BufferedInputStream(session.input()));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(session.output()));
            while (channel.isOpen()) {
                byte[] request = FrameCodec.readFrame(input, maxFrameSize);
                if (request == null) {
                    return;
                }
                byte[] response = Objects.requireNonNull(
                        requestProcessor.process(session.identity(), request), "request processor response");
                FrameCodec.writeFrame(output, response, maxFrameSize);
                frames++;
                if (LOG.isDebugEnabled()) LOG.debug("Frame exchanged peer={} request_bytes={} response_bytes={} frame_number={}",
                        peer, request.length, response.length, frames);
            }
        } catch (IOException | RuntimeException failure) {
            LOG.warn("Session failed session={} peer={} completed_frames={} elapsed_ms={}",
                    sessionId, peer, frames, (System.nanoTime() - started) / 1_000_000, failure);
            throw failure;
        } finally {
            if (LOG.isDebugEnabled()) LOG.debug("Session closed session={} peer={} completed_frames={} elapsed_ms={}",
                    sessionId, peer, frames, (System.nanoTime() - started) / 1_000_000);
        }
    }
}
