package io.setu.storage.log;

import net.openhft.chronicle.bytes.Bytes;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Payload writes and durable forcing, including Chronicle's mapped indexes and headers. */
class ChronicleFileSync implements AutoCloseable {
    private static final boolean LINUX = "Linux".equals(System.getProperty("os.name"));
    private final Path path;
    protected final FileChannel channel;
    private long forcedSize = -1;

    ChronicleFileSync(Path path) throws IOException {
        this.path = path;
        channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    void write(Bytes<?> destination, ByteBuffer payload) throws IOException {
        int size = payload.remaining();
        if (!LINUX) {
            // Windows does not guarantee coherence between mapped views and WriteFile.
            // Keep payloads in Chronicle's mapping on platforms without Linux's page cache.
            destination.write(payload.array(), payload.arrayOffset() + payload.position(), size);
            payload.position(payload.limit());
            return;
        }
        long position = destination.writePosition();
        while (payload.hasRemaining()) {
            int written = channel.write(payload, position);
            if (written <= 0) throw new IOException("No progress writing Chronicle payload: " + path);
            position += written;
        }
        destination.writeSkip(size);
    }

    void force() throws IOException {
        // Linux fsync/fdatasync cover shared mappings written through other FDs.
        // Keep a full metadata barrier for the first force and file growth. For
        // an already-sized file, fdatasync persists payload, indexes and headers
        // without waiting for timestamp-only inode journal transactions. Such
        // transactions can also contain unrelated files' ordered writes on ext4.
        // File size/block allocation needed to retrieve data is still durable
        // with fdatasync; this does not make acknowledgement asynchronous.
        if (LINUX) {
            long size = channel.size();
            channel.force(size != forcedSize);
            forcedSize = size; // Advance only after a successful durability barrier.
            return;
        }
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
