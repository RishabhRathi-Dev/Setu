package io.setu.storage.log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Simple record log with recovery by scanning length-prefixed records on open. */
public final class FileMessageLog implements MessageLog {
    private static final int MAX_RECORDS_PER_READ = 10_000;
    private final FileChannel channel;
    private final int maxRecordBytes;
    private final List<RecordPosition> index = new ArrayList<>();
    private boolean closed;

    public FileMessageLog(Path path, int maxRecordBytes) throws IOException {
        Objects.requireNonNull(path, "path");
        if (maxRecordBytes < 0) {
            throw new IllegalArgumentException("maxRecordBytes must be non-negative");
        }
        this.maxRecordBytes = maxRecordBytes;
        this.channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        recover();
    }

    @Override
    public synchronized long append(byte[] value) throws IOException {
        ensureOpen();
        Objects.requireNonNull(value, "value");
        if (value.length > maxRecordBytes) {
            throw new IOException("Record size " + value.length + " exceeds maximum " + maxRecordBytes);
        }
        long offset = index.size();
        long position = channel.size();
        ByteBuffer record = ByteBuffer.allocate(Integer.BYTES + value.length)
                .putInt(value.length).put(value);
        record.flip();
        try {
            channel.position(position);
            while (record.hasRemaining()) {
                channel.write(record);
            }
            channel.force(true);
        } catch (IOException failure) {
            try {
                channel.truncate(position);
                channel.position(position);
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
        index.add(new RecordPosition(position + Integer.BYTES, value.length));
        return offset;
    }

    @Override
    public synchronized long appendBatch(List<byte[]> values) throws IOException {
        ensureOpen();
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) throw new IllegalArgumentException("values must not be empty");
        long encodedBytes = 0;
        for (byte[] value : values) {
            Objects.requireNonNull(value, "value");
            if (value.length > maxRecordBytes)
                throw new IOException("Record size " + value.length + " exceeds maximum " + maxRecordBytes);
            encodedBytes += Integer.BYTES + (long) value.length;
            if (encodedBytes > Integer.MAX_VALUE) throw new IOException("Append batch is too large");
        }
        long position = channel.size();
        long firstOffset = index.size();
        ByteBuffer batch = ByteBuffer.allocate((int) encodedBytes);
        List<RecordPosition> positions = new ArrayList<>(values.size());
        long cursor = position;
        for (byte[] value : values) {
            batch.putInt(value.length).put(value);
            positions.add(new RecordPosition(cursor + Integer.BYTES, value.length));
            cursor += Integer.BYTES + (long) value.length;
        }
        batch.flip();
        try {
            channel.position(position);
            while (batch.hasRemaining()) channel.write(batch);
            channel.force(true);
        } catch (IOException failure) {
            try { channel.truncate(position); channel.position(position); }
            catch (IOException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            throw failure;
        }
        index.addAll(positions);
        return firstOffset;
    }

    @Override
    public synchronized List<LogRecord> read(long firstOffset, int maxBytes) throws IOException {
        ensureOpen();
        if (firstOffset < 0 || maxBytes < 0) {
            throw new IllegalArgumentException("offset and maxBytes must be non-negative");
        }
        if (maxBytes == 0) return List.of();
        List<LogRecord> records = new ArrayList<>();
        long bytesRead = 0;
        for (long offset = firstOffset; offset < index.size() && records.size() < MAX_RECORDS_PER_READ; offset++) {
            RecordPosition entry = index.get(Math.toIntExact(offset));
            if (!records.isEmpty() && bytesRead + entry.length > maxBytes) {
                break;
            }
            if (records.isEmpty() && entry.length > maxBytes) {
                break;
            }
            ByteBuffer data = ByteBuffer.allocate(entry.length);
            channel.position(entry.position);
            while (data.hasRemaining()) {
                if (channel.read(data) < 0) {
                    throw new IOException("Unexpected end of log while reading offset " + offset);
                }
            }
            records.add(new LogRecord(offset, data.array()));
            bytesRead += entry.length;
        }
        return List.copyOf(records);
    }

    @Override
    public synchronized long nextOffset() {
        return index.size();
    }

    private void recover() throws IOException {
        long position = 0;
        long size = channel.size();
        ByteBuffer lengthBuffer = ByteBuffer.allocate(Integer.BYTES);
        while (position + Integer.BYTES <= size) {
            lengthBuffer.clear();
            channel.position(position);
            if (!readFully(lengthBuffer)) {
                break;
            }
            int length = lengthBuffer.flip().getInt();
            if (length < 0 || length > maxRecordBytes || position + Integer.BYTES + length > size) {
                break;
            }
            index.add(new RecordPosition(position + Integer.BYTES, length));
            position += Integer.BYTES + length;
        }
        if (position != size) {
            channel.truncate(position);
        }
        channel.position(position);
    }

    private boolean readFully(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                return false;
            }
        }
        return true;
    }

    private void ensureOpen() throws IOException {
        if (closed || !channel.isOpen()) {
            throw new IOException("Message log is closed");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            channel.close();
        }
    }

    private record RecordPosition(long position, int length) { }
}
