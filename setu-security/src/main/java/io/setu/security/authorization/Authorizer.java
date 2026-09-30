package io.setu.security.authorization;

import io.setu.security.authentication.AuthenticationResult;

public interface Authorizer {

    AuthorizationDecision authorize(AuthenticationResult identity, AuthorizationContext context);
}