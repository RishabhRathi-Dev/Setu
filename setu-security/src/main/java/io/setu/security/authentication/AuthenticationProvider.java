package io.setu.security.authentication;

public interface AuthenticationProvider {

    AuthenticationResult authenticate(AuthenticationRequest authenticationRequest);

    AuthenticationMethod method();

}
