package io.setu.server.dispatcher;

import java.io.IOException;

/** Handles one complete protocol request and returns its response body. */
@FunctionalInterface
public interface RequestHandler {
    byte[] handle(byte[] request) throws IOException;
}
