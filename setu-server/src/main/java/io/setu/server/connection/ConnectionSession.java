package io.setu.server.connection;

import io.setu.security.authentication.AuthenticationResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/** An established connection and its optional authenticated peer identity. */
public final class ConnectionSession implements AutoCloseable {
    private final InputStream input;
    private final OutputStream output;
    private final AuthenticationResult identity;
    private final AutoCloseable resource;

    public ConnectionSession(InputStream input, OutputStream output,
                             AuthenticationResult identity, AutoCloseable resource) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.identity = identity;
        this.resource = Objects.requireNonNull(resource, "resource");
    }

    public InputStream input() {
        return input;
    }

    public OutputStream output() {
        return output;
    }

    public AuthenticationResult identity() {
        return identity;
    }

    @Override
    public void close() throws IOException {
        try {
            resource.close();
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Unable to close connection session", failure);
        }
    }
}
