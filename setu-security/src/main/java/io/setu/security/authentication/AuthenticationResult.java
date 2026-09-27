package io.setu.security.authentication;

import java.util.Map;

public record AuthenticationResult(
        String principal,
        AuthenticationMethod method,
        Map<String, Object> attributes
) {
    public AuthenticationResult {
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("Principal must not be null or blank");
        }

        if (method == null) {
            throw new IllegalArgumentException("Authentication method must not be null");
        }

        attributes = attributes == null
                ? Map.of()
                : Map.copyOf(attributes);
    }
}
