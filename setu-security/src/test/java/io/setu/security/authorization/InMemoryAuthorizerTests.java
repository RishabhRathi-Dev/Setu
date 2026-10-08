package io.setu.security.authorization;

import io.setu.security.authentication.AuthenticationMethod;
import io.setu.security.authentication.AuthenticationResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryAuthorizerTests {
    @Test
    void grantsAreExactMatchAndDefaultDeny() {
        InMemoryAuthorizer authorizer = new InMemoryAuthorizer();
        AuthenticationResult identity = new AuthenticationResult("orders-service", AuthenticationMethod.MTLS, Map.of());
        AuthorizationContext context = new AuthorizationContext("orders-service", "topic/orders", Permission.PRODUCE);
        assertFalse(authorizer.authorize(identity, context).allowed());
        authorizer.grant("orders-service", "topic/orders", Permission.PRODUCE);
        assertTrue(authorizer.authorize(identity, context).allowed());
        assertFalse(authorizer.authorize(identity, new AuthorizationContext(
                "orders-service", "topic/payments", Permission.PRODUCE)).allowed());
        authorizer.revoke("orders-service", "topic/orders", Permission.PRODUCE);
        assertFalse(authorizer.authorize(identity, context).allowed());
    }
}
