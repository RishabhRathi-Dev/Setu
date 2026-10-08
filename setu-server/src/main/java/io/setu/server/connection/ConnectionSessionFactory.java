package io.setu.server.connection;

import java.io.IOException;
import java.nio.channels.SocketChannel;

/** Establishes the transport and security session for an accepted TCP channel. */
@FunctionalInterface
public interface ConnectionSessionFactory {
    ConnectionSession open(SocketChannel channel) throws IOException;
}
