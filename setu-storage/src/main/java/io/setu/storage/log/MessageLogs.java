package io.setu.storage.log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Creates partition logs for a selected storage mode. */
public final class MessageLogs {
    private MessageLogs() { }

    public static MessageLog open(StorageMode mode, Path file, int maxRecordBytes) throws IOException {
        Objects.requireNonNull(mode, "mode");
        return switch (mode) {
            case IN_MEMORY -> new InMemoryMessageLog(maxRecordBytes);
            case FILE -> new FileMessageLog(Objects.requireNonNull(file, "file"), maxRecordBytes);
        };
    }
}
