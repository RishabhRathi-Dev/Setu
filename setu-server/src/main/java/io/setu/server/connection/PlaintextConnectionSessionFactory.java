package io.setu.server.connection;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.Objects;

/** Creates a plaintext session for local development or explicitly unsecured deployments. */
public final class PlaintextConnectionSessionFactory implements ConnectionSessionFactory {
    @Override
    public ConnectionSession open(SocketChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        return new ConnectionSession(
                channel.socket().getInputStream(),
                channel.socket().getOutputStream(),
                null,
                channel
        );
    }
}
