package io.setu.storage.log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Creates partition logs for a selected storage mode. */
public final class MessageLogs {
    public static final long DEFAULT_MAX_SEGMENT_BYTES = 1024L * 1024 * 1024;

    private MessageLogs() { }

    public static MessageLog open(StorageMode mode, Path file, int maxRecordBytes) throws IOException {
        return open(mode, file, maxRecordBytes, DEFAULT_MAX_SEGMENT_BYTES);
    }

    public static MessageLog open(StorageMode mode, Path file, int maxRecordBytes,
                                  long maxSegmentBytes) throws IOException {
        return open(mode, file, maxRecordBytes, maxSegmentBytes, new MemoryLogBudget(0));
    }

    public static MessageLog open(StorageMode mode, Path file, int maxRecordBytes,
                                  long maxSegmentBytes, MemoryLogBudget memoryBudget) throws IOException {
        Objects.requireNonNull(mode, "mode");
        return switch (mode) {
            case IN_MEMORY -> new InMemoryMessageLog(maxRecordBytes, memoryBudget);
            case FILE -> new SegmentedFileMessageLog(
                    Objects.requireNonNull(file, "file").toAbsolutePath().getParent(),
                    maxRecordBytes, maxSegmentBytes);
        };
    }
}
