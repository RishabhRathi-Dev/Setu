package io.setu.server.security;

import io.setu.security.authorization.Permission;

/** The resource and permission required to process a decoded protocol request. */
public record RequestAuthorizationMetadata(String resource, Permission permission,
                                            java.util.List<Requirement> additionalRequirements) {
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

    public record Requirement(String resource, Permission permission) {
        public Requirement {
            if (resource == null || resource.isBlank() || permission == null)
                throw new IllegalArgumentException("Resource and permission are required");
        }
    }

    public java.util.List<Requirement> requirements() {
        var result = new java.util.ArrayList<Requirement>(additionalRequirements.size() + 1);
        result.add(new Requirement(resource, permission));
        result.addAll(additionalRequirements);
        return java.util.List.copyOf(result);
    }
}
