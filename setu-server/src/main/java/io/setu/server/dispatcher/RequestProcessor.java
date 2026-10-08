package io.setu.server.dispatcher;

import io.setu.security.authentication.AuthenticationResult;

import java.io.IOException;

/** Processes one request using the authenticated identity of its connection, if present. */
@FunctionalInterface
public interface RequestProcessor {
    byte[] process(AuthenticationResult identity, byte[] request) throws IOException;
}
