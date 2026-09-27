package io.setu.security.authentication;

import javax.net.ssl.SSLSession;

public record AuthenticationRequest(SSLSession sslSession) {
}
