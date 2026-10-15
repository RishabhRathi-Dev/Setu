package io.setu.server.security;

import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.Command;
import io.setu.core.protocol.ProtocolCodec;
import io.setu.security.authorization.Permission;

/** Resolves the resource and least-privilege permission for each v1 broker command. */
public final class ProtocolRequestAuthorizationMetadataResolver implements RequestAuthorizationMetadataResolver {
    @Override
    public RequestAuthorizationMetadata resolve(byte[] request) throws java.io.IOException {
        return resolve(ProtocolCodec.decodeRequest(request));
    }

    @Override
    public RequestAuthorizationMetadata resolve(io.setu.storage.log.MessageValue.Frame request) throws java.io.IOException {
        return resolve(ProtocolCodec.decodeRequest(request));
    }

    private RequestAuthorizationMetadata resolve(BrokerRequest decoded) {
        return switch (decoded.command()) {
            case PRODUCE, PRODUCE_BATCH -> new RequestAuthorizationMetadata("topic/" + decoded.topic(), Permission.PRODUCE);
            case FETCH -> new RequestAuthorizationMetadata("topic/" + decoded.topic(), Permission.CONSUME);
            case CREATE_TOPIC -> new RequestAuthorizationMetadata("topic/" + decoded.topic(), Permission.TOPIC_CREATE);
            case COMMIT_OFFSET -> new RequestAuthorizationMetadata(
                    "consumer-group/" + decoded.groupId(), Permission.CONSUMER_GROUP_WRITE);
            case JOIN_GROUP -> new RequestAuthorizationMetadata(
                    "consumer-group/" + decoded.groupId(), Permission.CONSUMER_GROUP_CREATE);
            case LEAVE_GROUP, HEARTBEAT -> new RequestAuthorizationMetadata(
                    "consumer-group/" + decoded.groupId(), Permission.CONSUMER_GROUP_WRITE);
            case METADATA -> new RequestAuthorizationMetadata("broker", Permission.VIEW_METADATA);
        };
    }
}
