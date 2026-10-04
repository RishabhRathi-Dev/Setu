package io.setu.security.authorization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizationTests {

    @Test
    void rolesExposeTheirConfiguredPermissions() {
        assertTrue(Role.PRODUCER.hasPermission(Permission.PRODUCE));
        assertFalse(Role.PRODUCER.hasPermission(Permission.CONSUME));
        assertTrue(Role.CONSUMER.hasPermission(Permission.CONSUMER_GROUP_READ));
        assertTrue(Role.OPERATOR.hasPermission(Permission.TOPIC_DELETE));

        for (Permission permission : Permission.values()) {
            assertTrue(Role.ADMIN.hasPermission(permission), "ADMIN should include " + permission);
        }
    }

    @Test
    void authorizationContextRejectsInvalidFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuthorizationContext(" ", "topic/a", Permission.PRODUCE));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthorizationContext("client", "", Permission.PRODUCE));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthorizationContext("client", "topic/a", null));
    }
}
