package io.setu.server.security;

import io.setu.security.authorization.Permission;

/** The resource and permission required to process a decoded protocol request. */
public record RequestAuthorizationMetadata(String resource, Permission permission) {
    public RequestAuthorizationMetadata {
        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("resource must not be null or blank");
        }
        if (permission == null) {
            throw new IllegalArgumentException("permission must not be null");
        }
    }
}
