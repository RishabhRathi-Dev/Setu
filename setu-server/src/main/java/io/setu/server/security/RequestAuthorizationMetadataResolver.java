package io.setu.server.security;

import java.io.IOException;

/** Maps a decoded request to its Setu resource and permission. */
@FunctionalInterface
public interface RequestAuthorizationMetadataResolver {
    RequestAuthorizationMetadata resolve(byte[] request) throws IOException;
}
