package io.setu.client;

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
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Synchronous Setu protocol v1 client. Calls on one instance are serialized. */
public final class SetuClient implements AutoCloseable {
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final int maxFrameSize;
    private final AtomicInteger correlationIds = new AtomicInteger();

    private SetuClient(Socket socket, int maxFrameSize) throws IOException {
        if (maxFrameSize < 0) throw new IllegalArgumentException("maxFrameSize must be non-negative");
        this.socket = socket;
        this.maxFrameSize = maxFrameSize;
        this.input = new DataInputStream(socket.getInputStream());
        this.output = new DataOutputStream(socket.getOutputStream());
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
            return new SetuClient(tls, maxFrameSize);
        } catch (IOException | RuntimeException failure) {
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
            try { socket.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    public synchronized BrokerResponse request(BrokerRequest request) throws IOException {
        byte[] requestBytes = ProtocolCodec.encodeRequest(request);
        FrameCodec.writeFrame(output, requestBytes, maxFrameSize);
        byte[] responseBytes = FrameCodec.readFrame(input, maxFrameSize);
        if (responseBytes == null) throw new EOFException("Broker closed the connection before replying");
        BrokerResponse response = ProtocolCodec.decodeResponse(responseBytes);
        if (response.correlationId() != request.correlationId())
            throw new IOException("Response correlation id does not match request");
        if (response.command() != request.command())
            throw new IOException("Response command does not match request");
        return response;
    }

    public BrokerResponse createTopic(String topic, int partitions) throws IOException {
        return request(new BrokerRequest(nextId(), Command.CREATE_TOPIC, topic, -1, -1,
                -1, null, partitions, null, null));
    }

    public BrokerResponse produce(String topic, int partition, byte[] value) throws IOException {
        return request(new BrokerRequest(nextId(), Command.PRODUCE, topic, partition, -1,
                -1, value, 0, null, null));
    }

    public BrokerResponse fetch(String topic, int partition, long offset, int maxBytes) throws IOException {
        return request(new BrokerRequest(nextId(), Command.FETCH, topic, partition, offset,
                maxBytes, null, 0, null, null));
    }

    public BrokerResponse metadata() throws IOException {
        return request(new BrokerRequest(nextId(), Command.METADATA, null, -1, -1,
                -1, null, 0, null, null));
    }

    private int nextId() {
        return correlationIds.updateAndGet(current -> current == Integer.MAX_VALUE ? 0 : current + 1);
    }

    @Override
    public synchronized void close() throws IOException {
        socket.close();
    }
}
