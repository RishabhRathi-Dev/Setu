package io.setu.storage.uring;

import java.io.IOException;

/** Only unsupported or policy-denied setup permits the broker's startup fallback. */
public final class UringUnavailableException extends IOException {
    public UringUnavailableException(int errno) {
        super("io_uring setup unavailable: errno=" + errno);
        if (errno != 1 && errno != 13 && errno != 38 && errno != 95)
            throw new IllegalArgumentException("Not an availability error: " + errno);
    }
}
