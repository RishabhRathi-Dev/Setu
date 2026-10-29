package io.setu.server.security;

import io.setu.security.authorization.Permission;

public record AuthorizationRequirement(String resource, Permission permission) {
    public AuthorizationRequirement {
        if (resource == null || resource.isBlank() || permission == null)
            throw new IllegalArgumentException("Resource and permission are required");
    }
}
