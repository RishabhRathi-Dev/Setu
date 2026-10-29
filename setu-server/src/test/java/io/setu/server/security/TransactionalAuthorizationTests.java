package io.setu.server.security;

import io.setu.core.protocol.*;
import io.setu.security.authorization.Permission;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TransactionalAuthorizationTests {
    @Test void transactionRequiresOutputProducerInputTopicAndGroupPermissions() throws Exception {
        var request = new BrokerRequest(1, Command.TRANSACT, "output", 0, -1, -1, null, 0, null, null,
                List.of(new byte[]{1})).withOptions(new RequestOptions(new ProducerIdentity("identity", 0, 0),
                new ConsumerCommit("group", "member", "input", 0, 1, -1), AcknowledgementMode.DURABLE, -1));
        var metadata = new ProtocolRequestAuthorizationMetadataResolver().resolve(ProtocolCodec.encodeRequest(request));
        assertEquals(List.of(
                new AuthorizationRequirement("topic/output", Permission.PRODUCE),
                new AuthorizationRequirement("producer/identity", Permission.PRODUCE),
                new AuthorizationRequirement("topic/input", Permission.CONSUME),
                new AuthorizationRequirement("consumer-group/group", Permission.CONSUMER_GROUP_WRITE)),
                metadata.requirements());
    }
}
