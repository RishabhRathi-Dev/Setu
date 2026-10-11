package io.setu.server.security;

import io.setu.security.authentication.AuthenticationResult;
import io.setu.security.authorization.AuthorizationContext;
import io.setu.security.authorization.Authorizer;
import io.setu.security.quota.QuotaManager;
import io.setu.core.protocol.ErrorCode;
import io.setu.core.protocol.ProtocolCodec;
import io.setu.core.protocol.ProtocolException;
import io.setu.server.dispatcher.RequestHandler;
import io.setu.server.dispatcher.RequestProcessor;

import java.io.IOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Enforces authentication, authorization, and quotas before delegating to the broker dispatcher. */
public final class SecuredRequestProcessor implements RequestProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(SecuredRequestProcessor.class);
    private final Authorizer authorizer;
    private final QuotaManager quotaManager;
    private final RequestAuthorizationMetadataResolver metadataResolver;
    private final RequestHandler requestHandler;

    public SecuredRequestProcessor(Authorizer authorizer, QuotaManager quotaManager,
                                   RequestAuthorizationMetadataResolver metadataResolver,
                                   RequestHandler requestHandler) {
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.quotaManager = Objects.requireNonNull(quotaManager, "quotaManager");
        this.metadataResolver = Objects.requireNonNull(metadataResolver, "metadataResolver");
        this.requestHandler = Objects.requireNonNull(requestHandler, "requestHandler");
    }

    @Override
    public byte[] process(AuthenticationResult identity, byte[] request) throws IOException {
        Objects.requireNonNull(request, "request");
        if (identity == null) {
            LOG.warn("Authentication required frame_bytes={}", request.length);
            return ProtocolCodec.errorResponse(request, ErrorCode.AUTHENTICATION_FAILED,
                    "Authenticated identity is required");
        }

        RequestAuthorizationMetadata metadata;
        try {
            metadata = Objects.requireNonNull(metadataResolver.resolve(request), "authorization metadata");
        } catch (ProtocolException failure) {
            LOG.warn("Security metadata rejected principal={} frame_bytes={} reason={}", identity.principal(), request.length, failure.getMessage());
            return ProtocolCodec.errorResponse(request, failure.errorCode(), failure.getMessage());
        } catch (IOException failure) {
            LOG.warn("Security metadata resolution failed principal={} frame_bytes={}", identity.principal(), request.length, failure);
            return ProtocolCodec.errorResponse(request, ErrorCode.BAD_REQUEST, failure.getMessage());
        }

        try {
            AuthorizationContext context = new AuthorizationContext(
                    identity.principal(), metadata.resource(), metadata.permission());
            var authorization = authorizer.authorize(identity, context);
            if (authorization == null || !authorization.allowed()) {
                String reason = authorization == null ? "authorizer returned no decision" : authorization.reason();
                LOG.warn("Authorization denied principal={} resource={} permission={} reason={}",
                        identity.principal(), metadata.resource(), metadata.permission(), reason);
                return ProtocolCodec.errorResponse(request, ErrorCode.AUTHORIZATION_DENIED, reason);
            }

            var quota = quotaManager.check(identity.principal(), 1, request.length);
            if (!quota.allowed()) {
                LOG.warn("Quota rejected principal={} resource={} frame_bytes={} reason={}",
                        identity.principal(), metadata.resource(), request.length, quota.reason());
                return ProtocolCodec.errorResponse(request, ErrorCode.QUOTA_EXCEEDED, quota.reason());
            }
            if (LOG.isDebugEnabled()) LOG.debug("Security accepted principal={} resource={} permission={} frame_bytes={}",
                    identity.principal(), metadata.resource(), metadata.permission(), request.length);
            return requestHandler.handle(request);
        } catch (IOException failure) {
            LOG.error("Secured broker request failed principal={} resource={} permission={}",
                    identity.principal(), metadata.resource(), metadata.permission(), failure);
            return ProtocolCodec.errorResponse(request, ErrorCode.INTERNAL_ERROR, "Broker request failed");
        } catch (RuntimeException failure) {
            LOG.error("Security evaluation failed principal={} resource={} permission={}",
                    identity.principal(), metadata.resource(), metadata.permission(), failure);
            return ProtocolCodec.errorResponse(request, ErrorCode.INTERNAL_ERROR, "Request security failure");
        }
    }
}
