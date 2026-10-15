package io.setu.server.dispatcher;

import java.io.IOException;

/** Handles one complete protocol request and returns its response body. */
@FunctionalInterface
public interface RequestHandler {
    byte[] handle(byte[] request) throws IOException;

    default byte[] handle(io.setu.storage.log.MessageValue.Frame request) throws IOException {
        io.setu.core.protocol.RequestBuffers.reserveObjects(request.length());
        return handle(request.copy());
    }
}
