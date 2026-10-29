package io.setu.server.security;

import io.setu.storage.log.MessageFrame;
import io.setu.core.protocol.*;
import io.setu.security.authentication.AuthenticationMethod;
import io.setu.security.authentication.AuthenticationResult;
import io.setu.security.authorization.InMemoryAuthorizer;
import io.setu.security.authorization.Permission;
import io.setu.security.quota.QuotaManager;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CommandAuthorizationTests {
    private static final AuthenticationResult PRINCIPAL = new AuthenticationResult("alice", AuthenticationMethod.MTLS, Map.of());

    @Test void everyCommandRequiresAuthenticationAndEveryExactGrantBeforeQuotaOrDispatch() throws Exception {
        for (Command command : Command.values()) {
            byte[] encoded = ProtocolCodec.encodeRequest(request(command));
            for (boolean ownedFrame : new boolean[]{false, true}) {
                var policy = new InMemoryAuthorizer();
                var dispatched = new AtomicInteger();
                var charged = new AtomicInteger();
                var processor = new SecuredRequestProcessor(policy, new QuotaManager((principal, messages, bytes) -> {
                    assertEquals("alice", principal); assertEquals(1, messages); assertEquals(encoded.length, bytes);
                    charged.incrementAndGet(); return true;
                }), new ProtocolRequestAuthorizationMetadataResolver(), bytes -> {
                    dispatched.incrementAndGet();
                    var response = command == Command.JOIN_MANAGED_GROUP || command == Command.GROUP_ASSIGNMENT
                            ? new BrokerResponse(1, command, ErrorCode.NONE, "", -1, List.of(), Map.of(), new GroupAssignment(1, Map.of(0, 0L)))
                            : BrokerResponse.ok(1, command);
                    return ProtocolCodec.encodeResponse(response);
                });
                assertEquals(ErrorCode.AUTHENTICATION_FAILED, process(processor, null, encoded, ownedFrame).errorCode(), command.name());
                assertEquals(ErrorCode.AUTHORIZATION_DENIED, process(processor, PRINCIPAL, encoded, ownedFrame).errorCode(), command.name());
                var grants = required(command);
                for (var grant : grants) policy.grant("alice", grant.resource(), grant.permission());
                for (var missing : grants) {
                    policy.revoke("alice", missing.resource(), missing.permission());
                    // Another principal's identical permission must not satisfy the caller's missing grant.
                    policy.grant("bob", missing.resource(), missing.permission());
                    assertEquals(ErrorCode.AUTHORIZATION_DENIED, process(processor, PRINCIPAL, encoded, ownedFrame).errorCode(),
                            command + " missing " + missing);
                    policy.grant("alice", missing.resource(), missing.permission());
                }
                assertEquals(0, dispatched.get(), command.name());
                assertEquals(0, charged.get(), "denied requests must not consume quota: " + command);
                assertEquals(ErrorCode.NONE, process(processor, PRINCIPAL, encoded, ownedFrame).errorCode(), command.name());
                assertEquals(1, dispatched.get());
                assertEquals(1, charged.get());
                var deniedQuota = new SecuredRequestProcessor(policy, new QuotaManager((principal, messages, bytes) -> false),
                        new ProtocolRequestAuthorizationMetadataResolver(), bytes -> { fail("quota denial dispatched " + command); return bytes; });
                assertEquals(ErrorCode.QUOTA_EXCEEDED, process(deniedQuota, PRINCIPAL, encoded, ownedFrame).errorCode(), command.name());
            }
        }
    }

    private static BrokerResponse process(SecuredRequestProcessor processor, AuthenticationResult principal,
                                           byte[] encoded, boolean ownedFrame) throws Exception {
        byte[] result = ownedFrame ? processor.process(principal, MessageFrame.readFrom(
                new DataInputStream(new ByteArrayInputStream(encoded)), encoded.length)) : processor.process(principal, encoded);
        return ProtocolCodec.decodeResponse(result);
    }

    private static BrokerRequest request(Command command) {
        var request = new BrokerRequest(1, command, "orders", 0, 0, 1024, new byte[]{1}, 1,
                "group", "member", List.of(new byte[]{1}));
        if (command == Command.PRODUCE_IDEMPOTENT || command == Command.TRANSACT)
            return request.withOptions(new RequestOptions(new ProducerIdentity("producer", 0, 0),
                    command == Command.TRANSACT ? new ConsumerCommit("group", "member", "input", 0, 1, -1) : null,
                    AcknowledgementMode.DURABLE, -1));
        return request;
    }

    // Explicit public authorization contract, independent of the resolver's returned requirements.
    private static List<AuthorizationRequirement> required(Command command) {
        var produce = grant("topic/orders", Permission.PRODUCE);
        var consume = grant("topic/orders", Permission.CONSUME);
        var groupWrite = grant("consumer-group/group", Permission.CONSUMER_GROUP_WRITE);
        var groupCreate = grant("consumer-group/group", Permission.CONSUMER_GROUP_CREATE);
        return switch (command) {
            case PRODUCE, PRODUCE_BATCH, PRODUCE_CONFIGURED -> List.of(produce);
            case PRODUCE_IDEMPOTENT -> List.of(produce, grant("producer/producer", Permission.PRODUCE));
            case TRANSACT -> List.of(produce, grant("producer/producer", Permission.PRODUCE),
                    grant("topic/input", Permission.CONSUME), groupWrite);
            case FETCH -> List.of(consume, groupWrite);
            case COMMIT_OFFSET, FETCH_OFFSET, LEAVE_GROUP, HEARTBEAT -> List.of(groupWrite);
            case JOIN_GROUP -> List.of(groupCreate);
            case JOIN_MANAGED_GROUP -> List.of(groupCreate, consume);
            case GROUP_ASSIGNMENT -> List.of(groupWrite, consume);
            case CREATE_TOPIC -> List.of(grant("topic/orders", Permission.TOPIC_CREATE));
            case METADATA -> List.of(grant("broker", Permission.VIEW_METADATA));
        };
    }

    private static AuthorizationRequirement grant(String resource, Permission permission) {
        return new AuthorizationRequirement(resource, permission);
    }
}
