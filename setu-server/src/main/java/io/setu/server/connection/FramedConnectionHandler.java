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
    private io.setu.core.protocol.BufferBudget requestBudget = new io.setu.core.protocol.BufferBudget(128L * 1024 * 1024);
    private io.setu.core.protocol.BufferBudget responseBudget = new io.setu.core.protocol.BufferBudget(128L * 1024 * 1024);
    private int writeTimeoutMillis = 30_000;
    private static final java.util.concurrent.ScheduledThreadPoolExecutor WRITE_DEADLINES = writeDeadlines();

    private static java.util.concurrent.ScheduledThreadPoolExecutor writeDeadlines() {
        var timer = new java.util.concurrent.ScheduledThreadPoolExecutor(1, task ->
                Thread.ofPlatform().daemon().name("setu-write-deadlines").unstarted(task));
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    /** Called by the owning acceptor before it serves connections. */
    public void configureBudgets(long requests, long responses) {
        configureBudgets(requests, responses, 30_000);
    }

    public void configureBudgets(long requests, long responses, int writeTimeoutMillis) {
        if (writeTimeoutMillis <= 0) throw new IllegalArgumentException("write timeout must be positive");
        this.writeTimeoutMillis = writeTimeoutMillis;
        requestBudget = new io.setu.core.protocol.BufferBudget(requests);
        responseBudget = new io.setu.core.protocol.BufferBudget(responses);
    }

    public long inFlightRequestBytes() { return requestBudget.usedBytes(); }
    public long inFlightResponseBytes() { return responseBudget.usedBytes(); }

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
             var connectionCredit = requestBudget.reserve(17L * 1024);
             ConnectionSession session = sessionFactory.open(channel);
             var principalContext = MDC.putCloseable("principal", session.identity() == null
                     ? "anonymous" : session.identity().principal().toString())) {
            if (LOG.isDebugEnabled()) LOG.debug("Session opened peer={} max_frame_bytes={} tcp_nodelay={}", peer, maxFrameSize,
                    channel.socket().getTcpNoDelay());
            DataInputStream input = new DataInputStream(new BufferedInputStream(session.input()));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(session.output()));
            while (channel.isOpen()) {
                int first = input.read();
                if (first < 0) return;
                int length = (first << 24) | input.readUnsignedByte() << 16
                        | input.readUnsignedByte() << 8 | input.readUnsignedByte();
                if (length < 0 || length > maxFrameSize) throw new IOException("Invalid frame length: " + length);
                // Reserve frame + decoded payload allowance before allocating either body.
                try (var requestCredit = requestBudget.reserve(2L * length + 1024);
                     var requestContext = io.setu.core.protocol.RequestBuffers.activate(requestCredit);
                     var responseCredit = new io.setu.core.protocol.ResponseBuffers(responseBudget);
                     var context = responseCredit.activate()) {
                    byte[] request = new byte[length];
                    input.readFully(request);
                    byte[] response = Objects.requireNonNull(
                            requestProcessor.process(session.identity(), request), "request processor response");
                    responseCredit.ensure(response.length);
                    var deadline = WRITE_DEADLINES.schedule(() -> {
                        try { channel.close(); } catch (IOException ignored) { }
                    }, writeTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
                    try { FrameCodec.writeFrame(output, response, maxFrameSize); }
                    finally { deadline.cancel(false); }
                    frames++;
                } catch (IOException e) { throw e; }
                catch (Exception e) { throw new IOException("Frame processing failed", e); }
            }
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof io.setu.core.protocol.BufferBudget.Exhausted)
                LOG.debug("Frame admission rejected session={} peer={}", sessionId, peer);
            else LOG.warn("Session failed session={} peer={} completed_frames={} elapsed_ms={}",
                    sessionId, peer, frames, (System.nanoTime() - started) / 1_000_000, failure);
            throw failure;
        } finally {
            if (LOG.isDebugEnabled()) LOG.debug("Session closed session={} peer={} completed_frames={} elapsed_ms={}",
                    sessionId, peer, frames, (System.nanoTime() - started) / 1_000_000);
        }
    }
}
