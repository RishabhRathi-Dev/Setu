package io.setu.server.security;

import java.io.IOException;

/** Maps a decoded request to its Setu resource and permission. */
@FunctionalInterface
public interface RequestAuthorizationMetadataResolver {
    RequestAuthorizationMetadata resolve(byte[] request) throws IOException;

    default RequestAuthorizationMetadata resolve(io.setu.storage.log.MessageValue.Frame request) throws IOException {
        io.setu.core.protocol.RequestBuffers.reserveObjects(request.length());
        return resolve(request.copy());
    }
}
