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

/** Enforces authentication, authorization, and quotas before delegating to the broker dispatcher. */
public final class SecuredRequestProcessor implements RequestProcessor {
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
            return ProtocolCodec.errorResponse(request, ErrorCode.AUTHENTICATION_FAILED,
                    "Authenticated identity is required");
        }

        RequestAuthorizationMetadata metadata;
        try {
            metadata = Objects.requireNonNull(metadataResolver.resolve(request), "authorization metadata");
        } catch (ProtocolException failure) {
            return ProtocolCodec.errorResponse(request, failure.errorCode(), failure.getMessage());
        } catch (IOException failure) {
            return ProtocolCodec.errorResponse(request, ErrorCode.BAD_REQUEST, failure.getMessage());
        }

        try {
            AuthorizationContext context = new AuthorizationContext(
                    identity.principal(), metadata.resource(), metadata.permission());
            var authorization = authorizer.authorize(identity, context);
            if (authorization == null || !authorization.allowed()) {
                String reason = authorization == null ? "authorizer returned no decision" : authorization.reason();
                return ProtocolCodec.errorResponse(request, ErrorCode.AUTHORIZATION_DENIED, reason);
            }

            var quota = quotaManager.check(identity.principal(), 1, request.length);
            if (!quota.allowed()) {
                return ProtocolCodec.errorResponse(request, ErrorCode.QUOTA_EXCEEDED, quota.reason());
            }
            return requestHandler.handle(request);
        } catch (IOException failure) {
            return ProtocolCodec.errorResponse(request, ErrorCode.INTERNAL_ERROR, "Broker request failed");
        } catch (RuntimeException failure) {
            return ProtocolCodec.errorResponse(request, ErrorCode.INTERNAL_ERROR, "Request security failure");
        }
    }
}
