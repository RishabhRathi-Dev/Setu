package io.setu.server.security;

import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.Command;
import io.setu.core.protocol.ProtocolCodec;
import io.setu.security.authorization.Permission;

/** Resolves the resource and least-privilege permission for each broker command. */
public final class ProtocolRequestAuthorizationMetadataResolver implements RequestAuthorizationMetadataResolver {
    @Override
    public RequestAuthorizationMetadata resolve(byte[] request) throws java.io.IOException {
        return resolve(ProtocolCodec.decodeRequest(request));
    }

    @Override
    public RequestAuthorizationMetadata resolve(io.setu.storage.log.MessageFrame request) throws java.io.IOException {
        return resolve(ProtocolCodec.decodeRequest(request));
    }

    private RequestAuthorizationMetadata resolve(BrokerRequest decoded) {
        return switch (decoded.command()) {
            case PRODUCE, PRODUCE_BATCH, PRODUCE_CONFIGURED -> new RequestAuthorizationMetadata("topic/" + decoded.topic(), Permission.PRODUCE);
            case PRODUCE_IDEMPOTENT, TRANSACT -> {
                var requirements = new java.util.ArrayList<AuthorizationRequirement>();
                requirements.add(new AuthorizationRequirement("producer/" + decoded.options().producer().id(), Permission.PRODUCE));
                if (decoded.command() == Command.TRANSACT) {
                    var commit = decoded.options().commit();
                    requirements.add(new AuthorizationRequirement("topic/" + commit.topic(), Permission.CONSUME));
                    requirements.add(new AuthorizationRequirement("consumer-group/" + commit.groupId(), Permission.CONSUMER_GROUP_WRITE));
                }
                yield new RequestAuthorizationMetadata("topic/" + decoded.topic(), Permission.PRODUCE, requirements);
            }
            case FETCH -> new RequestAuthorizationMetadata("topic/" + decoded.topic(), Permission.CONSUME,
                    decoded.memberId() == null ? java.util.List.of() : java.util.List.of(
                            new AuthorizationRequirement("consumer-group/" + decoded.groupId(),
                                    Permission.CONSUMER_GROUP_WRITE)));
            case CREATE_TOPIC -> new RequestAuthorizationMetadata("topic/" + decoded.topic(), Permission.TOPIC_CREATE);
            case COMMIT_OFFSET, FETCH_OFFSET -> new RequestAuthorizationMetadata(
                    "consumer-group/" + decoded.groupId(), Permission.CONSUMER_GROUP_WRITE);
            case JOIN_GROUP -> new RequestAuthorizationMetadata(
                    "consumer-group/" + decoded.groupId(), Permission.CONSUMER_GROUP_CREATE);
            case JOIN_MANAGED_GROUP -> new RequestAuthorizationMetadata(
                    "consumer-group/" + decoded.groupId(), Permission.CONSUMER_GROUP_CREATE,
                    java.util.List.of(new AuthorizationRequirement("topic/" + decoded.topic(), Permission.CONSUME)));
            case GROUP_ASSIGNMENT -> new RequestAuthorizationMetadata(
                    "consumer-group/" + decoded.groupId(), Permission.CONSUMER_GROUP_WRITE,
                    java.util.List.of(new AuthorizationRequirement("topic/" + decoded.topic(), Permission.CONSUME)));
            case LEAVE_GROUP, HEARTBEAT -> new RequestAuthorizationMetadata(
                    "consumer-group/" + decoded.groupId(), Permission.CONSUMER_GROUP_WRITE);
            case METADATA -> new RequestAuthorizationMetadata("broker", Permission.VIEW_METADATA);
        };
    }
}
