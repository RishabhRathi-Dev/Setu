package io.setu.security.authorization;

public record AuthorizationContext(
        String principal,
        String resource,
        Permission permission
) {
    public AuthorizationContext {
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("Principal must not be null or blank");
        }

        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("Resource must not be null or blank");
        }

        if (permission == null) {
            throw new IllegalArgumentException("Permission must not be null");
        }
    }
}
