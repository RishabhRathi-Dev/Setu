package io.setu.server.dispatcher;

import io.setu.security.authentication.AuthenticationResult;

import java.io.IOException;

/** Processes one request using the authenticated identity of its connection, if present. */
@FunctionalInterface
public interface RequestProcessor {
    byte[] process(AuthenticationResult identity, byte[] request) throws IOException;

    default byte[] process(AuthenticationResult identity, io.setu.storage.log.MessageFrame request) throws IOException {
        io.setu.core.protocol.RequestBuffers.reserveObjects(request.length());
        return process(identity, request.copy());
    }

    static RequestProcessor plaintext(RequestHandler handler) {
        java.util.Objects.requireNonNull(handler, "handler");
        return new RequestProcessor() {
            public byte[] process(AuthenticationResult identity, byte[] request) throws IOException {
                return handler.handle(request);
            }
            public byte[] process(AuthenticationResult identity, io.setu.storage.log.MessageFrame request) throws IOException {
                return handler.handle(request);
            }
        };
    }
}
