package io.setu.storage.log;

import java.io.IOException;

/** An append was rejected before changing the log because its memory budget was exhausted. */
public final class LogCapacityExceededException extends IOException {
    public LogCapacityExceededException(String message) { super(message); }
}
