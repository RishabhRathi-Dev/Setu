package io.setu.security.authentication;

import java.util.Map;

public record AuthenticationResult(
        String principal,
        AuthenticationMethod method,
        Map<String, Object> attributes
) {
}
