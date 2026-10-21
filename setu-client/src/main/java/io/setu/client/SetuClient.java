package io.setu.client;

import io.setu.core.logging.LogText;
import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.Command;
import io.setu.core.protocol.FrameCodec;
import io.setu.core.protocol.ProtocolCodec;
import io.setu.security.tls.TlsConfiguration;
import io.setu.security.tls.TlsContextFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Synchronous Setu protocol v1 client. Calls on one instance are serialized. */
public final class SetuClient implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SetuClient.class);
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final int maxFrameSize;
    private volatile boolean closed;
    private final AtomicInteger correlationIds = new AtomicInteger();
    private final ProtocolCodec.ProduceBatchEncoder batchEncoder = new ProtocolCodec.ProduceBatchEncoder();
    private final ProtocolCodec.ResponseReader responseReader = new ProtocolCodec.ResponseReader();

    private SetuClient(Socket socket, int maxFrameSize) throws IOException {
        if (maxFrameSize < 0) throw new IllegalArgumentException("maxFrameSize must be non-negative");
        this.socket = socket;
        socket.setTcpNoDelay(true);
        this.maxFrameSize = maxFrameSize;
        this.input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        if (LOG.isDebugEnabled()) LOG.debug("Client connected peer={} local={} max_frame_bytes={} read_timeout_ms={} tcp_nodelay={}",
                socket.getRemoteSocketAddress(), socket.getLocalSocketAddress(), maxFrameSize,
                socket.getSoTimeout(), socket.getTcpNoDelay());
    }

    public static SetuClient connectTls(String host, int port, SSLContext context,
                                        List<String> enabledProtocols, List<String> enabledCipherSuites,
                                        int connectTimeoutMillis, int readTimeoutMillis,
                                        int maxFrameSize) throws IOException {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(context, "context");
        if (connectTimeoutMillis < 0 || readTimeoutMillis < 0)
            throw new IllegalArgumentException("timeouts must be non-negative");
        Socket transport = new Socket();
        try {
            transport.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            transport.setSoTimeout(readTimeoutMillis);
            transport.setTcpNoDelay(true);
            SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(transport, host, port, true);
            SSLParameters parameters = tls.getSSLParameters();
            if (enabledProtocols != null && !enabledProtocols.isEmpty())
                parameters.setProtocols(enabledProtocols.toArray(String[]::new));
            if (enabledCipherSuites != null && !enabledCipherSuites.isEmpty())
                parameters.setCipherSuites(enabledCipherSuites.toArray(String[]::new));
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.setUseClientMode(true);
            tls.setSoTimeout(readTimeoutMillis);
            tls.startHandshake();
            LOG.info("TLS connected host={} port={} protocol={} cipher={}", host, port,
                    tls.getSession().getProtocol(), tls.getSession().getCipherSuite());
            return new SetuClient(tls, maxFrameSize);
        } catch (IOException | RuntimeException failure) {
            LOG.error("TLS connection failed host={} port={} connect_timeout_ms={} read_timeout_ms={}",
                    host, port, connectTimeoutMillis, readTimeoutMillis, failure);
            try { transport.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    public static SetuClient connectTls(String host, int port, TlsConfiguration configuration,
                                        TlsContextFactory contextFactory, int connectTimeoutMillis,
                                        int readTimeoutMillis, int maxFrameSize) throws IOException {
        Objects.requireNonNull(configuration, "configuration");
        if (!configuration.enabled()) throw new IllegalArgumentException("TLS must be enabled");
        SSLContext context = Objects.requireNonNull(contextFactory, "contextFactory").create(configuration);
        return connectTls(host, port, context, configuration.enabledProtocols(),
                configuration.enabledCipherSuites(), connectTimeoutMillis, readTimeoutMillis, maxFrameSize);
    }

    public static SetuClient connectPlain(String host, int port, int connectTimeoutMillis,
                                          int readTimeoutMillis, int maxFrameSize) throws IOException {
        if (connectTimeoutMillis < 0 || readTimeoutMillis < 0)
            throw new IllegalArgumentException("timeouts must be non-negative");
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);
            return new SetuClient(socket, maxFrameSize);
        } catch (IOException | RuntimeException failure) {
            LOG.error("Plaintext connection failed host={} port={} connect_timeout_ms={} read_timeout_ms={}",
                    host, port, connectTimeoutMillis, readTimeoutMillis, failure);
            try { socket.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    public synchronized BrokerResponse request(BrokerRequest request) throws IOException {
        return exchange(request.command(), request.correlationId(), request.topic(), request.partition(),
                request.offset(), ProtocolCodec.encodeRequest(request));
    }

    private synchronized BrokerResponse exchange(Command command, int correlation, String topic, int partition,
                                                  long offset, byte[] requestBytes) throws IOException {
        if (closed) throw new IOException("Client connection is closed");
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        try {
            if (LOG.isDebugEnabled()) LOG.debug("Sending command={} correlation={} peer={} topic={} partition={} offset={} frame_bytes={}",
                    command, correlation, socket.getRemoteSocketAddress(), LogText.escape(topic), partition, offset, requestBytes.length);
            FrameCodec.writeFrame(output, requestBytes, maxFrameSize);
            BrokerResponse response = responseReader.readFrom(input, maxFrameSize, command);
            if (response == null) throw new EOFException("Broker closed the connection before replying");
            if (response.correlationId() != correlation)
                throw new IOException("Response correlation id does not match request");
            if (response.command() != command)
                throw new IOException("Response command does not match request");
            if (LOG.isDebugEnabled()) LOG.debug("Received command={} correlation={} error={} offset={} records={} frame_bytes={} elapsed_us={}",
                    response.command(), response.correlationId(), response.errorCode(), response.offset(),
                    response.records().size(), responseReader.frameLength(), (System.nanoTime() - started) / 1000);
            if (response.errorCode() != io.setu.core.protocol.ErrorCode.NONE)
                LOG.warn("Broker rejected command={} correlation={} peer={} topic={} partition={} error={} reason={}",
                        command, correlation, socket.getRemoteSocketAddress(),
                        LogText.escape(topic), partition, response.errorCode(), LogText.escape(response.errorMessage()));
            return response;
        } catch (IOException | RuntimeException failure) {
            closed = true;
            try { socket.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            LOG.error("Client request failed command={} correlation={} peer={} topic={} partition={} offset={}",
                    command, correlation, socket.getRemoteSocketAddress(),
                    LogText.escape(topic), partition, offset, failure);
            throw failure;
        } finally {
            if (closed) {
                batchEncoder.clear();
                responseReader.clear();
            }
        }
    }

    public BrokerResponse createTopic(String topic, int partitions) throws IOException {
        return request(new BrokerRequest(nextId(), Command.CREATE_TOPIC, topic, -1, -1,
                -1, null, partitions, null, null));
    }

    public BrokerResponse produce(String topic, int partition, byte[] value) throws IOException {
        return request(new BrokerRequest(nextId(), Command.PRODUCE, topic, partition, -1,
                -1, value, 0, null, null));
    }

    /** Writes a contiguous batch; the response offset is the first assigned offset. */
    public synchronized BrokerResponse produceBatch(String topic, int partition, List<byte[]> values) throws IOException {
        int correlation = nextId();
        return exchange(Command.PRODUCE_BATCH, correlation, topic, partition, -1,
                batchEncoder.encode(correlation, topic, partition, values));
    }

    public BrokerResponse fetch(String topic, int partition, long offset, int maxBytes) throws IOException {
        return request(new BrokerRequest(nextId(), Command.FETCH, topic, partition, offset,
                maxBytes, null, 0, null, null));
    }

    /** Fetches as a joined group member so an in-memory broker can track this member's read progress. */
    public BrokerResponse fetch(String groupId, String memberId, String topic, int partition,
                                long offset, int maxBytes) throws IOException {
        return request(new BrokerRequest(nextId(), Command.FETCH, topic, partition, offset,
                maxBytes, null, 0, groupId, memberId));
    }

    public BrokerResponse joinGroup(String groupId, String topic, String memberId) throws IOException {
        return request(new BrokerRequest(nextId(), Command.JOIN_GROUP, topic, -1, -1,
                -1, null, 0, groupId, memberId));
    }

    /** Waits up to waitMillis for data; the socket read timeout must exceed this value. */
    public BrokerResponse fetch(String topic, int partition, long offset, int maxBytes, int waitMillis) throws IOException {
        return request(new BrokerRequest(nextId(), Command.FETCH, topic, partition, offset,
                maxBytes, null, 0, null, null).withWaitMillis(waitMillis));
    }

    public BrokerResponse fetch(String group, String member, String topic, int partition,
                                long offset, int maxBytes, int waitMillis) throws IOException {
        return request(new BrokerRequest(nextId(), Command.FETCH, topic, partition, offset,
                maxBytes, null, 0, group, member).withWaitMillis(waitMillis));
    }

    public BrokerResponse heartbeat(String groupId, String memberId) throws IOException {
        return request(new BrokerRequest(nextId(), Command.HEARTBEAT, null, -1, -1,
                -1, null, 0, groupId, memberId));
    }

    public BrokerResponse leaveGroup(String groupId, String memberId) throws IOException {
        return request(new BrokerRequest(nextId(), Command.LEAVE_GROUP, null, -1, -1,
                -1, null, 0, groupId, memberId));
    }

    /** Acknowledges the next offset to consume; in-memory storage reclaims through this offset. */
    public BrokerResponse acknowledge(String groupId, String memberId, String topic,
                                      int partition, long nextOffset) throws IOException {
        return request(new BrokerRequest(nextId(), Command.COMMIT_OFFSET, topic, partition, nextOffset,
                0, null, 0, groupId, memberId));
    }

    public BrokerResponse fetchOffset(String groupId, String memberId, String topic, int partition) throws IOException {
        return request(new BrokerRequest(nextId(), Command.FETCH_OFFSET, topic, partition, 0,
                0, null, 0, groupId, memberId));
    }

    public BrokerResponse metadata() throws IOException {
        return request(new BrokerRequest(nextId(), Command.METADATA, null, -1, -1,
                -1, null, 0, null, null));
    }

    private int nextId() {
        return correlationIds.updateAndGet(current -> current == Integer.MAX_VALUE ? 0 : current + 1);
    }

    @Override
    public void close() throws IOException {
        closed = true;
        if (LOG.isDebugEnabled()) LOG.debug("Client closing peer={}", socket.getRemoteSocketAddress());
        // Socket close must not wait for the monitor held by a blocked exchange.
        try { socket.close(); }
        finally {
            synchronized (this) {
                batchEncoder.clear();
                responseReader.clear();
            }
        }
    }
}
