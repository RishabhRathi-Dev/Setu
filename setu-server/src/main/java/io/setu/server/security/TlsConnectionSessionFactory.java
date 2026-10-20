package io.setu.server.security;

import io.setu.security.authentication.AuthenticationProvider;
import io.setu.security.authentication.AuthenticationRequest;
import io.setu.security.tls.TlsContextFactory;
import io.setu.security.tls.TlsConfiguration;
import io.setu.server.connection.ConnectionSession;
import io.setu.server.connection.ConnectionSessionFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.Objects;

/** Establishes server-side TLS, requires a client certificate, then authenticates the peer. */
public final class TlsConnectionSessionFactory implements ConnectionSessionFactory {
    private final TlsConfiguration configuration;
    private final SSLContext sslContext;
    private final AuthenticationProvider authenticationProvider;

    public TlsConnectionSessionFactory(TlsConfiguration configuration, SSLContext sslContext,
                                       AuthenticationProvider authenticationProvider) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
        this.authenticationProvider = Objects.requireNonNull(authenticationProvider, "authenticationProvider");
        if (!configuration.enabled()) {
            throw new IllegalArgumentException("TLS must be enabled for TlsConnectionSessionFactory");
        }
        if (!configuration.clientAuthenticationRequired()) {
            throw new IllegalArgumentException("Client certificate authentication must be required");
        }
    }

    public TlsConnectionSessionFactory(TlsConfiguration configuration, TlsContextFactory tlsContextFactory,
                                       AuthenticationProvider authenticationProvider) {
        this(configuration,
                Objects.requireNonNull(tlsContextFactory, "tlsContextFactory").create(configuration),
                authenticationProvider);
    }

    @Override
    public ConnectionSession open(SocketChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Socket transport = channel.socket();
        InetSocketAddress peer = (InetSocketAddress) channel.getRemoteAddress();
        SSLSocket tlsSocket = (SSLSocket) sslContext.getSocketFactory().createSocket(
                transport, peer.getHostString(), peer.getPort(), true);
        boolean established = false;
        try {
            tlsSocket.setUseClientMode(false);
            tlsSocket.setSoTimeout(transport.getSoTimeout());
            SSLParameters parameters = tlsSocket.getSSLParameters();
            if (!configuration.enabledProtocols().isEmpty()) {
                parameters.setProtocols(configuration.enabledProtocols().toArray(String[]::new));
            }
            if (!configuration.enabledCipherSuites().isEmpty()) {
                parameters.setCipherSuites(configuration.enabledCipherSuites().toArray(String[]::new));
            }
            tlsSocket.setSSLParameters(parameters);
            tlsSocket.setNeedClientAuth(true);
            tlsSocket.startHandshake();

            var verifiedSession = tlsSocket.getSession();
            var identity = authenticationProvider.authenticate(new AuthenticationRequest(verifiedSession));
            established = true;
            // Server-mode mTLS validates the client chain and DNS SAN identity, not a server hostname.
            // CodeQL models SSLEngine server mode but currently misses SSLSocket.setUseClientMode(false).
            // codeql[java/unsafe-cert-trust] Client authentication is mandatory; HTTPS endpoint checks belong on clients.
            return new ConnectionSession(tlsSocket.getInputStream(), tlsSocket.getOutputStream(), identity, tlsSocket);
        } finally {
            if (!established) {
                tlsSocket.close();
            }
        }
    }
}
