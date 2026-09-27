package io.setu.security.authentication;

import javax.net.ssl.SSLSession;

public record AuthenticationRequest(SSLSession sslSession) {

    public AuthenticationRequest {
        if (sslSession == null) {
            throw new IllegalArgumentException("SSL session must not be null");
        }
    }

}
