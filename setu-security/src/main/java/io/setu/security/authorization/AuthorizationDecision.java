package io.setu.security.authorization;

public record AuthorizationDecision(
        boolean allowed,
        String reason
) {

    public static AuthorizationDecision allow() {
        return new AuthorizationDecision(
                true,
                "Authorized"
        );
    }

    public static AuthorizationDecision deny(String reason) {

        return new AuthorizationDecision(
                false,
                reason
        );
    }
}