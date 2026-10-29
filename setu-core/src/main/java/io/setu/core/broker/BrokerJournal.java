package io.setu.core.broker;

import io.setu.storage.log.MessageLog;
import io.setu.storage.log.MessageLogs;
import io.setu.storage.log.StorageMode;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.zip.CRC32C;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

/** Owns journal generations. Callers exclude all state mutations during checkpoint publication. */
final class BrokerJournal implements AutoCloseable {
    private static final int MAGIC = 0x53435031;
    private final Path root;
    private final long blockBytes;
    private final FileChannel lockChannel;
    private final java.nio.channels.FileLock lock;
    private MessageLog log;
    private Path generation;
    private final java.util.concurrent.atomic.AtomicLong appendedBytes = new java.util.concurrent.atomic.AtomicLong();
    private volatile long diskBytes;
    private volatile long compactions;
    private JournalFault fault = stage -> { };

    BrokerJournal(Path root, long blockBytes) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.blockBytes = blockBytes;
        Files.createDirectories(root);
        lockChannel = FileChannel.open(root.resolve(".broker.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
            if (lock == null) throw new IOException("Broker metadata is already owned");
            Path current = root.resolve("CURRENT");
            if (!Files.exists(current) && Files.exists(root.resolve("COMPACTED")))
                throw new IOException("Missing authoritative journal generation pointer");
            generation = Files.exists(current) ? checkedGeneration(Files.readString(current).trim()) : this.root;
            if (!generation.equals(this.root) && !Files.isRegularFile(generation.resolve("checkpoint")))
                throw new IOException("Missing broker checkpoint");
            log = MessageLogs.open(StorageMode.FILE, generation, 15 * 1024 * 1024, blockBytes);
            refreshDiskBytes();
        } catch (IOException | RuntimeException | Error failure) {
            if (log != null) try { log.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            try { lockChannel.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private Path checkedGeneration(String name) throws IOException {
        if (!name.matches("generation-[0-9a-f-]{36}")) throw new IOException("Invalid journal generation");
        Path path = root.resolve(name);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
            throw new IOException("Missing or unsafe journal generation");
        return path;
    }

    void restore(JournalCheckpointReader reader) throws IOException {
        if (generation.equals(root)) return;
        if (log.nextOffset() == 0 || !java.util.Arrays.equals(log.read(0, 1).getFirst().value(), new byte[]{0}))
            throw new IOException("Missing checkpoint journal boundary");
        try (var checked = new CheckedInputStream(new java.io.BufferedInputStream(Files.newInputStream(generation.resolve("checkpoint"))), new CRC32C());
             var input = new DataInputStream(checked)) {
            if (input.readInt() != MAGIC) throw new IOException("Unsupported broker checkpoint");
            reader.read(input);
            long checksum = checked.getChecksum().getValue();
            if (input.readLong() != checksum || input.read() != -1) throw new IOException("Invalid checkpoint checksum or trailer");
        }
    }

    void compact(JournalCheckpointWriter writer) throws IOException {
        Path next = Files.createDirectory(root.resolve("generation-" + UUID.randomUUID()));
        MessageLog replacement = null;
        try {
            Path checkpoint = next.resolve("checkpoint");
            try (var checked = new CheckedOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(checkpoint, StandardOpenOption.CREATE_NEW)), new CRC32C());
                 var output = new DataOutputStream(checked)) {
                output.writeInt(MAGIC);
                writer.write(output);
                long checksum = checked.getChecksum().getValue();
                output.writeLong(checksum);
            }
            fault.at("checkpoint-written");
            force(checkpoint);
            replacement = MessageLogs.open(StorageMode.FILE, next, 15 * 1024 * 1024, blockBytes);
            // The empty queue must have a durable initialized document before it becomes authoritative.
            replacement.append(new byte[]{0});
            forceDirectory(next);
            fault.at("checkpoint-forced");
            Path temporary = root.resolve("CURRENT.tmp");
            Files.writeString(temporary, next.getFileName().toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            force(temporary);
            fault.at("manifest-forced");
            Files.move(temporary, root.resolve("CURRENT"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(root);
            fault.at("manifest-published");
            MessageLog previous = log;
            log = replacement;
            replacement = null;
            generation = next;
            appendedBytes.set(0);
            compactions++;
            previous.close();
            // A missing pointer must never silently reopen a cleaned legacy journal as empty state.
            Path marker = root.resolve("COMPACTED");
            Files.writeString(marker, "checkpoint layout v1\n", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            force(marker);
            forceDirectory(root);
            // Only recognized journal-owned files are removed, never arbitrary files under the data root.
            fault.at("before-cleanup");
            cleanupObsolete();
            fault.at("after-cleanup");
            refreshDiskBytes();
        } finally {
            if (replacement != null) replacement.close();
        }
    }

    private void cleanupObsolete() throws IOException {
        try (var paths = Files.list(root)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (path.equals(generation)) continue;
                if (name.matches("generation-[0-9a-f-]{36}") && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    try (var children = Files.list(path)) {
                        for (Path child : children.toList()) if (ownedFile(child)) Files.delete(child);
                    }
                    try (var children = Files.list(path)) {
                        if (children.findAny().isEmpty()) Files.delete(path);
                    }
                } else if (ownedFile(path)) Files.delete(path);
            }
        }
    }

    private static boolean ownedFile(Path path) {
        String name = path.getFileName().toString();
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && (name.matches("[0-9]{8}F?\\.cq4")
                || name.equals("metadata.cq4t") || name.equals(".setu.lock") || name.equals("checkpoint"));
    }

    static void force(Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    private static void forceDirectory(Path path) throws IOException {
        if (!System.getProperty("os.name").startsWith("Windows"))
            try (var channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
    }

    void append(byte[] value) throws IOException { log.append(value); appendedBytes.addAndGet(value.length); }
    long nextOffset() { return log.nextOffset(); }
    java.util.List<io.setu.storage.log.LogRecord> read(long offset, int bytes) throws IOException { return log.read(offset, bytes); }
    long appendedBytes() { return appendedBytes.get(); }
    void recovered(byte[] value) { appendedBytes.addAndGet(value.length); }
    boolean hasCheckpoint() { return !generation.equals(root); }
    long diskBytes() { return diskBytes; }
    long compactions() { return compactions; }
    void fault(JournalFault fault) { this.fault = fault; }
    void refreshDiskBytes() throws IOException {
        try (var paths = Files.walk(root, 2)) {
            long size = 0;
            for (Path path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) size += Files.size(path);
            diskBytes = size;
        }
    }
    @Override public void close() throws IOException {
        try { log.close(); }
        finally { try { lock.release(); } finally { lockChannel.close(); } }
    }
}
