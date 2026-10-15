package io.setu.storage.log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Creates partition logs for a selected storage mode. */
public final class MessageLogs {
    public static final long DEFAULT_QUEUE_BLOCK_BYTES = 64L * 1024 * 1024;

    private MessageLogs() { }

    public static MessageLog open(StorageMode mode, Path directory, int maxRecordBytes) throws IOException {
        return open(mode, directory, maxRecordBytes, DEFAULT_QUEUE_BLOCK_BYTES);
    }

    public static MessageLog open(StorageMode mode, Path directory, int maxRecordBytes,
                                  long queueBlockBytes) throws IOException {
        return open(mode, directory, maxRecordBytes, queueBlockBytes, new MemoryLogBudget(0));
    }

    public static MessageLog open(StorageMode mode, Path directory, int maxRecordBytes,
                                  long queueBlockBytes, MemoryLogBudget memoryBudget) throws IOException {
        return open(mode, directory, maxRecordBytes, queueBlockBytes, memoryBudget, false);
    }

    public static MessageLog open(StorageMode mode, Path directory, int maxRecordBytes,
                                  long queueBlockBytes, MemoryLogBudget memoryBudget, boolean timedRetention) throws IOException {
        Objects.requireNonNull(mode, "mode");
        return switch (mode) {
            case IN_MEMORY -> new InMemoryMessageLog(maxRecordBytes, memoryBudget, timedRetention);
            case FILE -> new GroupCommitMessageLog(new ChronicleMessageLog(
                    Objects.requireNonNull(directory, "directory").toAbsolutePath(),
                    maxRecordBytes, queueBlockBytes), 32L * 1024 * 1024, 1024, 250_000);
        };
    }
}
