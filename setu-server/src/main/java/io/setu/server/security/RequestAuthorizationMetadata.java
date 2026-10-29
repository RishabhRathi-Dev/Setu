package io.setu.server.security;

import io.setu.security.authorization.Permission;

/** The resource and permission required to process a decoded protocol request. */
public record RequestAuthorizationMetadata(String resource, Permission permission,
                                            java.util.List<AuthorizationRequirement> additionalRequirements) {
    public RequestAuthorizationMetadata(String resource, Permission permission) {
        this(resource, permission, java.util.List.of());
    }

    public RequestAuthorizationMetadata {
        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("resource must not be null or blank");
        }
        if (permission == null) {
            throw new IllegalArgumentException("permission must not be null");
        }
        additionalRequirements = java.util.List.copyOf(additionalRequirements);
    }

    public java.util.List<AuthorizationRequirement> requirements() {
        var result = new java.util.ArrayList<AuthorizationRequirement>(additionalRequirements.size() + 1);
        result.add(new AuthorizationRequirement(resource, permission));
        result.addAll(additionalRequirements);
        return java.util.List.copyOf(result);
    }
}
