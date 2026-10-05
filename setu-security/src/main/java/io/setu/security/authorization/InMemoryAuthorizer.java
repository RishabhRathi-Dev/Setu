package io.setu.security.authorization;

import io.setu.security.authentication.AuthenticationResult;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Exact-match, default-deny ACL implementation for a single broker process. */
public final class InMemoryAuthorizer implements Authorizer, AccessPolicy {
    private final Set<Grant> grants = ConcurrentHashMap.newKeySet();

    public void grant(String principal, String resource, Permission permission) {
        grants.add(new Grant(require(principal, "principal"), require(resource, "resource"),
                Objects.requireNonNull(permission, "permission")));
    }

    public void revoke(String principal, String resource, Permission permission) {
        grants.remove(new Grant(require(principal, "principal"), require(resource, "resource"),
                Objects.requireNonNull(permission, "permission")));
    }

    @Override
    public boolean allows(String principal, String resource, Permission permission) {
        return grants.contains(new Grant(principal, resource, permission));
    }

    @Override
    public AuthorizationDecision authorize(AuthenticationResult identity, AuthorizationContext context) {
        if (identity == null || context == null || !identity.principal().equals(context.principal())) {
            return AuthorizationDecision.deny("Identity and authorization context do not match");
        }
        if (allows(context.principal(), context.resource(), context.permission())) {
            return AuthorizationDecision.allow();
        }
        return AuthorizationDecision.deny("No ACL grant for principal, resource, and permission");
    }

    private static String require(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
        return value;
    }

    private record Grant(String principal, String resource, Permission permission) { }
}
