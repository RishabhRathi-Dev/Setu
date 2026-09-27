package io.setu.security.authentication;

import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLSession;

@Slf4j
public class MtlsAuthenticator implements AuthenticationProvider{
    @Override
    public AuthenticationResult authenticate(AuthenticationRequest authenticationRequest) {
        SSLSession sslSession = authenticationRequest.sslSession();
        String protocol = sslSession.getProtocol();
        log.info("Protocol {}",protocol);
        return null;
    }

    @Override
    public AuthenticationMethod method() {
        return null;
    }
}