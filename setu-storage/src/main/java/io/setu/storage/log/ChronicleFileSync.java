package io.setu.storage.log;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** JDK mapped-file forcing, including Windows where Chronicle's POSIX msync is a no-op. */
final class ChronicleFileSync implements AutoCloseable {
    private final Path path;
    private final FileChannel channel;

    ChronicleFileSync(Path path) throws IOException {
        this.path = path;
        channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    void force() throws IOException {
        long size = channel.size();
        if (size > 0) {
            // Force the complete mapping: Chronicle can update indexes outside the new payload range.
            // Scope each view to this force rather than retaining a second mapping while
            // Chronicle grows and releases its own views on other threads.
            try (var arena = Arena.ofConfined()) {
                MemorySegment mapping = channel.map(FileChannel.MapMode.READ_WRITE, 0, size, arena);
                mapping.force();
            } catch (RuntimeException failure) {
                throw new IOException("Chronicle mapped-file force failed: " + path + " file=" + size, failure);
            }
        }
        channel.force(true);
    }

    @Override public void close() throws IOException {
        channel.close();
    }
}
