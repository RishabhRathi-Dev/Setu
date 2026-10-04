package io.setu.security.authentication;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticationModelTests {

    @Test
    void authenticationRequestRequiresAnSslSession() {
        assertThrows(IllegalArgumentException.class, () -> new AuthenticationRequest(null));
    }

    @Test
    void authenticationResultCopiesAttributesAndDefaultsNullToEmpty() {
        var attributes = new HashMap<String, Object>();
        attributes.put("region", "west");

        var result = new AuthenticationResult("client", AuthenticationMethod.MTLS, attributes);
        attributes.put("later", true);

        assertEquals(Map.of("region", "west"), result.attributes());
        assertThrows(UnsupportedOperationException.class,
                () -> result.attributes().put("new", "value"));
        assertTrue(new AuthenticationResult("client", AuthenticationMethod.MTLS, null)
                .attributes().isEmpty());
    }

    @Test
    void authenticationResultRejectsMissingIdentityOrMethod() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticationResult(" ", AuthenticationMethod.MTLS, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticationResult("client", null, Map.of()));
    }
}
