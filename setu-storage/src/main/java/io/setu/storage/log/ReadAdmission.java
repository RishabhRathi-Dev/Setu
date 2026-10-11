package io.setu.storage.log;

import java.io.IOException;

/** Reserve cumulative fetch bytes before materializing records; no core/transport dependency in storage. */
@FunctionalInterface
public interface ReadAdmission {
    ReadAdmission UNLIMITED = bytes -> { };
    void reserve(long bytes) throws IOException;
}
