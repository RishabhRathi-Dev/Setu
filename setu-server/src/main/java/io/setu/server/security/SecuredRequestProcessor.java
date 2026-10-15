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
        return process(identity, request, null);
    }

    @Override
    public byte[] process(AuthenticationResult identity, io.setu.storage.log.MessageValue.Frame request) throws IOException {
        return process(identity, null, Objects.requireNonNull(request, "request"));
    }

    private byte[] process(AuthenticationResult identity, byte[] request,
                           io.setu.storage.log.MessageValue.Frame frame) throws IOException {
        int length = frame == null ? request.length : frame.length();
        if (identity == null) {
            LOG.warn("Authentication required frame_bytes={}", length);
            return error(request, frame, ErrorCode.AUTHENTICATION_FAILED,
                    "Authenticated identity is required");
        }

        RequestAuthorizationMetadata metadata;
        try {
            metadata = Objects.requireNonNull(frame == null ? metadataResolver.resolve(request)
                    : metadataResolver.resolve(frame), "authorization metadata");
        } catch (io.setu.core.protocol.BufferBudget.Exhausted failure) {
            return error(request, frame, ErrorCode.QUOTA_EXCEEDED, failure.getMessage());
        } catch (ProtocolException failure) {
            LOG.warn("Security metadata rejected principal={} frame_bytes={} reason={}", identity.principal(), length, failure.getMessage());
            return error(request, frame, failure.errorCode(), failure.getMessage());
        } catch (IOException failure) {
            LOG.warn("Security metadata resolution failed principal={} frame_bytes={}", identity.principal(), length, failure);
            return error(request, frame, ErrorCode.BAD_REQUEST, failure.getMessage());
        }

        try {
            AuthorizationContext context = new AuthorizationContext(
                    identity.principal(), metadata.resource(), metadata.permission());
            var authorization = authorizer.authorize(identity, context);
            if (authorization == null || !authorization.allowed()) {
                String reason = authorization == null ? "authorizer returned no decision" : authorization.reason();
                LOG.warn("Authorization denied principal={} resource={} permission={} reason={}",
                        identity.principal(), metadata.resource(), metadata.permission(), reason);
                return error(request, frame, ErrorCode.AUTHORIZATION_DENIED, reason);
            }

            var quota = quotaManager.check(identity.principal(), 1, length);
            if (!quota.allowed()) {
                LOG.warn("Quota rejected principal={} resource={} frame_bytes={} reason={}",
                        identity.principal(), metadata.resource(), length, quota.reason());
                return error(request, frame, ErrorCode.QUOTA_EXCEEDED, quota.reason());
            }
            if (LOG.isDebugEnabled()) LOG.debug("Security accepted principal={} resource={} permission={} frame_bytes={}",
                    identity.principal(), metadata.resource(), metadata.permission(), length);
            return frame == null ? requestHandler.handle(request) : requestHandler.handle(frame);
        } catch (io.setu.core.protocol.BufferBudget.Exhausted failure) {
            return error(request, frame, ErrorCode.QUOTA_EXCEEDED, failure.getMessage());
        } catch (IOException failure) {
            LOG.error("Secured broker request failed principal={} resource={} permission={}",
                    identity.principal(), metadata.resource(), metadata.permission(), failure);
            return error(request, frame, ErrorCode.INTERNAL_ERROR, "Broker request failed");
        } catch (RuntimeException failure) {
            LOG.error("Security evaluation failed principal={} resource={} permission={}",
                    identity.principal(), metadata.resource(), metadata.permission(), failure);
            return error(request, frame, ErrorCode.INTERNAL_ERROR, "Request security failure");
        }
    }

    private static byte[] error(byte[] request, io.setu.storage.log.MessageValue.Frame frame,
                                ErrorCode code, String message) {
        return frame == null ? ProtocolCodec.errorResponse(request, code, message)
                : ProtocolCodec.errorResponse(frame, code, message);
    }
}
