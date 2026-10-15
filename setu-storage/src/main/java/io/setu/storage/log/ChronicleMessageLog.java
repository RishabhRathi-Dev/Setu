package io.setu.storage.log;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.MappedBytes;
import net.openhft.chronicle.bytes.SyncMode;
import net.openhft.chronicle.core.io.BackgroundResourceReleaser;
import net.openhft.chronicle.core.time.TimeProvider;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.RollCycle;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Chronicle Queue partition log with dense Setu offsets independent of Chronicle roll-cycle indexes. */
public final class ChronicleMessageLog implements MessageLog {
    private static final long FORMAT = 0x5345545500000001L;
    private static final int HEADER_BYTES = 20;
    private static final int MAX_RECORDS_PER_READ = 10_000;
    private static final int TARGET_DOCUMENT_BYTES = 1024 * 1024;
    private final int maxRecordBytes;
    private final SingleChronicleQueue queue;
    private final ExcerptAppender appender;
    private final ExcerptTailer tailer;
    private final FileChannel lockChannel;
    private final FileLock partitionLock;
    private final Map<Path, ChronicleFileSync> syncFiles = new LinkedHashMap<>();
    private long[] offsets = new long[128];
    private long[] indexes = new long[128];
    private int documents;
    private long nextOffset;
    private boolean closed;
    private IOException failed;
    private Path activeFile;

    public ChronicleMessageLog(Path directory, int maxRecordBytes, long blockBytes) throws IOException {
        this(directory, maxRecordBytes, blockBytes, RollCycles.FAST_DAILY, System::currentTimeMillis);
    }

    ChronicleMessageLog(Path directory, int maxRecordBytes, long blockBytes, RollCycle rollCycle,
                        TimeProvider timeProvider) throws IOException {
        Objects.requireNonNull(directory, "directory");
        if (maxRecordBytes < 0 || blockBytes <= 0) throw new IllegalArgumentException("Invalid queue limits");
        this.maxRecordBytes = maxRecordBytes;
        Files.createDirectories(directory);
        try (var files = Files.list(directory)) {
            if (files.anyMatch(path -> path.getFileName().toString().endsWith(".log")))
                throw new IOException("Legacy .log partition requires offline migration to Chronicle Queue: " + directory);
        }
        System.setProperty("chronicle.analytics.disable", "true");
        if (System.getProperty("background.releaser") == null) System.setProperty("background.releaser", "false");
        SingleChronicleQueue opened = null;
        ExcerptAppender writer = null;
        ExcerptTailer reader = null;
        FileChannel locked = null;
        FileLock lock = null;
        try {
            locked = FileChannel.open(directory.resolve(".setu.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try { lock = locked.tryLock(); }
            catch (java.nio.channels.OverlappingFileLockException busy) { throw new IOException("Partition is already open: " + directory, busy); }
            if (lock == null) throw new IOException("Partition is already open: " + directory);
            opened = SingleChronicleQueueBuilder.binary(directory).blockSize(Math.max(64L * 1024 * 1024, blockBytes))
                    .rollCycle(rollCycle).timeProvider(timeProvider).syncMode(SyncMode.NONE).build();
            writer = opened.createAppender();
            reader = opened.createTailer();
            // All access, including close, is serialized by this adapter's monitor.
            writer.singleThreadedCheckDisabled(true);
            reader.singleThreadedCheckDisabled(true);
            queue = opened;
            appender = writer;
            tailer = reader;
            lockChannel = locked;
            partitionLock = lock;
            recover();
        } catch (Throwable failure) {
            for (AutoCloseable resource : new AutoCloseable[]{reader, writer, opened, lock, locked}) {
                if (resource != null) try { resource.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            }
            if (failure instanceof Error error) throw error;
            throw failure instanceof IOException io ? io : new IOException("Cannot open Chronicle partition " + directory, failure);
        }
    }

    @Override public synchronized long append(byte[] value) throws IOException {
        return appendValues(List.of(MessageValue.copyOf(value)));
    }

    @Override public synchronized long appendBatch(List<byte[]> values) throws IOException {
        return appendValues(values.stream().map(MessageValue::copyOf).toList());
    }

    @Override public synchronized long appendValues(List<MessageValue> values) throws IOException {
        ensureOpen();
        List<MessageValue> owned = List.copyOf(values);
        if (owned.isEmpty()) throw new IllegalArgumentException("Empty batch");
        for (MessageValue value : owned)
            if (value.length() > maxRecordBytes) throw new IOException("Record exceeds maximum " + maxRecordBytes);
        long first = nextOffset;
        long next = Math.addExact(first, owned.size());
        try {
            var dirtyFiles = new LinkedHashSet<Path>();
            int start = 0;
            while (start < owned.size()) {
                int end = start;
                long encoded = HEADER_BYTES;
                while (end < owned.size()) {
                    long bytes = Integer.BYTES + (long) owned.get(end).length();
                    if (end > start && encoded + bytes > TARGET_DOCUMENT_BYTES) break;
                    encoded += bytes;
                    end++;
                }
                reserveIndex();
                if (activeFile != null) dirtyFiles.add(activeFile);
                try (var document = appender.writingDocument()) {
                    try {
                        Bytes<?> bytes = document.wire().bytes();
                        bytes.writeLong(FORMAT).writeLong(first + start).writeInt(end - start);
                        var output = new DataOutputStream(bytes.outputStream());
                        for (int i = start; i < end; i++) {
                            MessageValue value = owned.get(i);
                            bytes.writeInt(value.length());
                            value.writeTo(output);
                        }
                    } catch (Throwable failure) { document.rollbackOnClose(); throw failure; }
                }
                offsets[documents] = first + start;
                indexes[documents++] = appender.lastIndexAppended();
                Path file = ((MappedBytes) appender.wire().bytes()).mappedFile().file().toPath();
                if (!syncFiles.containsKey(file)) syncFiles.put(file, new ChronicleFileSync(file));
                dirtyFiles.add(file);
                if (!file.equals(activeFile)) {
                    Path metadata = queue.file().toPath().resolve("metadata.cq4t");
                    if (Files.exists(metadata) && !syncFiles.containsKey(metadata))
                        syncFiles.put(metadata, new ChronicleFileSync(metadata));
                    if (Files.exists(metadata)) dirtyFiles.add(metadata);
                }
                activeFile = file;
                start = end;
            }
            // This also forces native index/header writes, including rollovers and mapping boundaries.
            for (Path file : dirtyFiles) syncFiles.get(file).force();
            var files = syncFiles.entrySet().iterator();
            while (files.hasNext()) {
                var entry = files.next();
                if (!entry.getKey().equals(activeFile)) { entry.getValue().close(); files.remove(); }
            }
            nextOffset = next;
            return first;
        } catch (Throwable failure) {
            failed = failure instanceof IOException io ? io : new IOException("Chronicle append failed", failure);
            if (failure instanceof Error error) throw error;
            throw failed;
        }
    }

    @Override public synchronized List<LogRecord> read(long offset, int maxBytes) throws IOException {
        return read(offset, maxBytes, ReadAdmission.UNLIMITED);
    }

    @Override public synchronized List<LogRecord> read(long offset, int maxBytes, ReadAdmission admission) throws IOException {
        ensureOpen();
        if (offset < 0 || maxBytes < 0) throw new IllegalArgumentException("Negative read limit");
        if (offset >= nextOffset || maxBytes == 0) return List.of();
        var records = new ArrayList<LogRecord>();
        long payloadBytes = 0;
        int position = Arrays.binarySearch(offsets, 0, documents, offset);
        if (position < 0) position = -position - 2;
        try {
            while (position < documents && records.size() < MAX_RECORDS_PER_READ) {
                if (!tailer.moveToIndex(indexes[position])) throw new IOException("Missing Chronicle document for offset " + offset);
                try (var document = tailer.readingDocument()) {
                    if (!document.isPresent()) throw new IOException("Missing Chronicle document");
                    Bytes<?> bytes = document.wire().bytes();
                    int count = readHeader(bytes, offsets[position]);
                    int skip = (int) (offset - offsets[position]);
                    for (int i = 0; i < skip; i++) skipValue(bytes);
                    long start = bytes.readPosition();
                    int take = 0;
                    while (skip + take < count && records.size() + take < MAX_RECORDS_PER_READ) {
                        long before = bytes.readPosition();
                        int length = readLength(bytes);
                        if (payloadBytes + length > maxBytes) { bytes.readPosition(before); break; }
                        payloadBytes += length;
                        bytes.readSkip(length);
                        take++;
                    }
                    if (take == 0) break;
                    int encoded = Math.toIntExact(bytes.readPosition() - start);
                    admission.reserve(2L * payloadBytes + 160L * (records.size() + take) + 22);
                    bytes.readPosition(start);
                    var frame = MessageValue.Frame.readFrom(new DataInputStream(bytes.inputStream()), encoded);
                    ByteBuffer input = frame.cursor().order(java.nio.ByteOrder.nativeOrder());
                    for (int i = 0; i < take; i++) {
                        int length = input.getInt();
                        var value = frame.valueAt(input.position(), length);
                        input.position(input.position() + length);
                        records.add(new LogRecord(offset++, value));
                    }
                    if (skip + take < count) break;
                }
                position++;
            }
            return List.copyOf(records);
        } catch (RuntimeException failure) { throw new IOException("Chronicle fetch failed", failure); }
    }

    @Override public synchronized long nextOffset() { return nextOffset; }

    private void recover() throws IOException {
        tailer.toStart();
        while (true) {
            try (var document = tailer.readingDocument()) {
                if (!document.isPresent()) break;
                Bytes<?> bytes = document.wire().bytes();
                int count = readHeader(bytes, nextOffset);
                for (int i = 0; i < count; i++) skipValue(bytes);
                if (bytes.readRemaining() != 0) throw new IOException("Trailing bytes in Chronicle document");
                reserveIndex();
                offsets[documents] = nextOffset;
                indexes[documents++] = document.index();
                nextOffset = Math.addExact(nextOffset, count);
            }
        }
    }

    private int readHeader(Bytes<?> bytes, long expectedOffset) throws IOException {
        if (bytes.readRemaining() < HEADER_BYTES || bytes.readLong() != FORMAT || bytes.readLong() != expectedOffset)
            throw new IOException("Invalid Chronicle Setu document header or offset sequence");
        int count = bytes.readInt();
        if (count <= 0 || count > bytes.readRemaining() / Integer.BYTES)
            throw new IOException("Invalid Chronicle document record count");
        return count;
    }

    private int readLength(Bytes<?> bytes) throws IOException {
        if (bytes.readRemaining() < Integer.BYTES) throw new IOException("Truncated Chronicle record length");
        int length = bytes.readInt();
        if (length < 0 || length > maxRecordBytes || length > bytes.readRemaining())
            throw new IOException("Invalid Chronicle record length");
        return length;
    }

    private void skipValue(Bytes<?> bytes) throws IOException { bytes.readSkip(readLength(bytes)); }

    private void reserveIndex() throws IOException {
        if (documents == Integer.MAX_VALUE - 8) throw new IOException("Chronicle batch index is full");
        if (documents == offsets.length) {
            int size = (int) Math.min(Integer.MAX_VALUE - 8L, offsets.length * 2L);
            offsets = Arrays.copyOf(offsets, size);
            indexes = Arrays.copyOf(indexes, size);
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Message log is closed");
        if (failed != null) throw new IOException("Chronicle log failed; reopen before retrying", failed);
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        var resources = new ArrayList<AutoCloseable>(List.of(tailer, appender, queue));
        resources.addAll(syncFiles.values());
        resources.add(partitionLock);
        resources.add(lockChannel);
        for (AutoCloseable resource : resources) {
            try { resource.close(); }
            catch (Exception error) {
                if (failure == null) failure = error instanceof IOException io ? io : new IOException("Chronicle close failed", error);
                else failure.addSuppressed(error);
            }
        }
        syncFiles.clear();
        BackgroundResourceReleaser.releasePendingResources();
        if (failure != null) throw failure;
    }
}
