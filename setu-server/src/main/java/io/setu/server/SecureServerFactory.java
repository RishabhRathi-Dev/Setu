package io.setu.server;

import io.setu.core.broker.Broker;
import io.setu.security.authentication.AuthenticationProvider;
import io.setu.security.authorization.Authorizer;
import io.setu.security.quota.QuotaManager;
import io.setu.security.tls.TlsConfiguration;
import io.setu.security.tls.TlsContextFactory;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.RequestHandler;
import io.setu.server.dispatcher.BrokerRequestHandler;
import io.setu.server.security.ProtocolRequestAuthorizationMetadataResolver;
import io.setu.server.security.RequestAuthorizationMetadataResolver;
import io.setu.server.security.SecuredRequestProcessor;
import io.setu.server.security.TlsConnectionSessionFactory;
import io.setu.server.tcp.TcpServer;
import io.setu.server.tcp.TcpServerOptions;

import java.net.InetSocketAddress;
import java.util.Objects;

/** Composition root for a TLS/mTLS server with authorization and quota enforcement. */
public final class SecureServerFactory {
    private SecureServerFactory() { }

    public static TcpServer create(InetSocketAddress bindAddress, int maxFrameSize,
                                   TlsConfiguration tlsConfiguration,
                                   TlsContextFactory tlsContextFactory,
                                   AuthenticationProvider authenticationProvider,
                                   Authorizer authorizer,
                                   QuotaManager quotaManager,
                                   RequestAuthorizationMetadataResolver metadataResolver,
                                   RequestHandler requestHandler) {
        return create(bindAddress, maxFrameSize, tlsConfiguration, tlsContextFactory, authenticationProvider,
                authorizer, quotaManager, metadataResolver, requestHandler, TcpServerOptions.defaults());
    }

    public static TcpServer create(InetSocketAddress bindAddress, int maxFrameSize,
                                   TlsConfiguration tlsConfiguration,
                                   TlsContextFactory tlsContextFactory,
                                   AuthenticationProvider authenticationProvider,
                                   Authorizer authorizer,
                                   QuotaManager quotaManager,
                                   RequestAuthorizationMetadataResolver metadataResolver,
                                   RequestHandler requestHandler,
                                   TcpServerOptions serverOptions) {
        Objects.requireNonNull(bindAddress, "bindAddress");
        TlsConnectionSessionFactory tlsSessions = new TlsConnectionSessionFactory(
                tlsConfiguration, tlsContextFactory, authenticationProvider);
        SecuredRequestProcessor securedRequests = new SecuredRequestProcessor(
                authorizer, quotaManager, metadataResolver, requestHandler);
        FramedConnectionHandler connections = new FramedConnectionHandler(
                maxFrameSize, tlsSessions, securedRequests);
        return new TcpServer(bindAddress, connections, serverOptions);
    }

    /** Creates a secure server wired to the built-in protocol decoder and a broker implementation. */
    public static TcpServer createBroker(InetSocketAddress bindAddress, int maxFrameSize,
                                         TlsConfiguration tlsConfiguration,
                                         TlsContextFactory tlsContextFactory,
                                         AuthenticationProvider authenticationProvider,
                                         Authorizer authorizer,
                                         QuotaManager quotaManager,
                                         Broker broker) {
        return createBroker(bindAddress, maxFrameSize, tlsConfiguration, tlsContextFactory,
                authenticationProvider, authorizer, quotaManager, broker, TcpServerOptions.defaults());
    }

    public static TcpServer createBroker(InetSocketAddress bindAddress, int maxFrameSize,
                                         TlsConfiguration tlsConfiguration,
                                         TlsContextFactory tlsContextFactory,
                                         AuthenticationProvider authenticationProvider,
                                         Authorizer authorizer,
                                         QuotaManager quotaManager,
                                         Broker broker,
                                         TcpServerOptions serverOptions) {
        return create(bindAddress, maxFrameSize, tlsConfiguration, tlsContextFactory,
                authenticationProvider, authorizer, quotaManager,
                new ProtocolRequestAuthorizationMetadataResolver(), new BrokerRequestHandler(broker), serverOptions);
    }
}
