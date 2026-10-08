package io.setu.server.connection;

import java.io.IOException;
import java.nio.channels.SocketChannel;

/** Owns the protocol lifecycle for one accepted connection. */
@FunctionalInterface
public interface ConnectionHandler {
    void handle(SocketChannel channel) throws IOException;
}
