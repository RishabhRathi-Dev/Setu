package io.setu.storage.log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Simple record log with recovery by scanning length-prefixed records on open. */
public final class FileMessageLog implements MessageLog {
    private static final Logger LOG = LoggerFactory.getLogger(FileMessageLog.class);
    private final Path path;
    private static final int MAX_RECORDS_PER_READ = 10_000;
    private final FileChannel channel;
    private final int maxRecordBytes;
    private static final int READ_WINDOW_BYTES = 1024 * 1024;
    private long[] positions = new long[1024];
    private int recordCount;
    private long endPosition;
    private boolean closed;

    public FileMessageLog(Path path, int maxRecordBytes) throws IOException {
        Objects.requireNonNull(path, "path");
        if (maxRecordBytes < 0) {
            throw new IllegalArgumentException("maxRecordBytes must be non-negative");
        }
        this.maxRecordBytes = maxRecordBytes;
        this.path = path.toAbsolutePath().normalize();
        this.channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        try { recover(); }
        catch (IOException | RuntimeException failure) {
            LOG.error("File log recovery failed path={} recovered_records={} recovered_bytes={}", this.path, recordCount, endPosition, failure);
            try { channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    @Override
    public synchronized long append(byte[] value) throws IOException {
        ensureOpen();
        Objects.requireNonNull(value, "value");
        if (value.length > maxRecordBytes) {
            throw new IOException("Record size " + value.length + " exceeds maximum " + maxRecordBytes);
        }
        long offset = recordCount;
        reserveIndex(1);
        long position = endPosition;
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
        positions[recordCount++] = position;
        endPosition += Integer.BYTES + (long) value.length;
        if (LOG.isDebugEnabled()) LOG.debug("File record persisted path={} offset={} payload_bytes={} end_position={} forced=true",
                path, offset, value.length, endPosition);
        return offset;
    }

    @Override
    public synchronized long appendBatch(List<byte[]> values) throws IOException {
        return appendValues(values.stream().map(MessageValue::copyOf).toList());
    }

    @Override
    public synchronized long appendValues(List<MessageValue> values) throws IOException {
        ensureOpen();
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) throw new IllegalArgumentException("values must not be empty");
        long encodedBytes = 0;
        for (MessageValue value : values) {
            Objects.requireNonNull(value, "value");
            if (value.length() > maxRecordBytes)
                throw new IOException("Record size " + value.length() + " exceeds maximum " + maxRecordBytes);
            encodedBytes += Integer.BYTES + (long) value.length();
            if (encodedBytes > Integer.MAX_VALUE) throw new IOException("Append batch is too large");
        }
        reserveIndex(values.size());
        long position = endPosition;
        long firstOffset = recordCount;
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        ByteBuffer batch = ByteBuffer.allocate((int) encodedBytes);
        for (MessageValue value : values) {
            batch.putInt(value.length());
            value.copyTo(batch);
        }
        batch.flip();
        try {
            channel.position(position);
            while (batch.hasRemaining()) channel.write(batch);
            channel.force(true);
        } catch (IOException failure) {
            try { channel.truncate(position); channel.position(position); }
            catch (IOException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            LOG.error("File batch append failed path={} first_offset={} records={} encoded_bytes={} rollback_position={}",
                    path, firstOffset, values.size(), encodedBytes, position, failure);
            throw failure;
        }
        for (MessageValue value : values) {
            positions[recordCount++] = endPosition;
            endPosition += Integer.BYTES + (long) value.length();
        }
        if (LOG.isDebugEnabled()) LOG.debug("File batch persisted path={} first_offset={} records={} encoded_bytes={} end_position={} forced=true elapsed_us={}",
                path, firstOffset, values.size(), encodedBytes, endPosition, (System.nanoTime() - started) / 1000);
        return firstOffset;
    }

    @Override
    public synchronized List<LogRecord> read(long firstOffset, int maxBytes) throws IOException {
        return read(firstOffset, maxBytes, ReadAdmission.UNLIMITED);
    }

    @Override
    public synchronized List<LogRecord> read(long firstOffset, int maxBytes, ReadAdmission admission) throws IOException {
        ensureOpen();
        if (firstOffset < 0 || maxBytes < 0) {
            throw new IllegalArgumentException("offset and maxBytes must be non-negative");
        }
        if (maxBytes == 0 || firstOffset >= recordCount) return List.of();
        List<LogRecord> records = new ArrayList<>();
        long bytesRead = 0;
        int offset = (int) firstOffset;
        while (offset < recordCount && records.size() < MAX_RECORDS_PER_READ) {
            int end = offset;
            long windowEnd = positions[offset];
            while (end < recordCount && records.size() + end - offset < MAX_RECORDS_PER_READ) {
                long recordEnd = end + 1 == recordCount ? endPosition : positions[end + 1];
                int length = Math.toIntExact(recordEnd - positions[end] - Integer.BYTES);
                if (bytesRead + length > maxBytes) break;
                if (end > offset && recordEnd - positions[offset] > READ_WINDOW_BYTES) break;
                bytesRead += length;
                windowEnd = recordEnd;
                end++;
            }
            if (end == offset) break;
            // File window + private payload arrays + encoded reply and record metadata.
            admission.reserve(3L * bytesRead + 96L * (records.size() + end - offset) + 22);
            ByteBuffer data = ByteBuffer.allocate(Math.toIntExact(windowEnd - positions[offset]));
            channel.position(positions[offset]);
            if (!readFully(data)) throw new IOException("Unexpected end of log while reading offset " + offset);
            data.flip();
            while (offset < end) {
                int length = data.getInt();
                long recordEnd = offset + 1 == recordCount ? endPosition : positions[offset + 1];
                if (length != recordEnd - positions[offset] - Integer.BYTES)
                    throw new IOException("Record length changed while reading offset " + offset);
                MessageValue value = MessageValue.readFrom(data, length);
                records.add(new LogRecord(offset++, value));
            }
        }
        if (LOG.isDebugEnabled()) LOG.debug("File fetch path={} first_offset={} max_bytes={} records={} payload_bytes={}",
                path, firstOffset, maxBytes, records.size(), bytesRead);
        return List.copyOf(records);
    }

    @Override
    public synchronized long nextOffset() {
        return recordCount;
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
            reserveIndex(1);
            positions[recordCount++] = position;
            position += Integer.BYTES + length;
        }
        if (position != size) {
            LOG.warn("Discarding incomplete or invalid log tail path={} original_bytes={} valid_bytes={} discarded_bytes={} valid_records={}",
                    path, size, position, size - position, recordCount);
            channel.truncate(position);
        }
        channel.position(position);
        endPosition = position;
        LOG.info("File log recovered path={} records={} bytes={} max_record_bytes={}", path, recordCount, endPosition, maxRecordBytes);
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

    private void reserveIndex(int additional) throws IOException {
        long required = recordCount + (long) additional;
        if (required > Integer.MAX_VALUE - 8) throw new IOException("Segment record index is full");
        if (required > positions.length)
            positions = Arrays.copyOf(positions, (int) Math.max(required,
                    Math.min(Integer.MAX_VALUE - 8L, positions.length * 2L)));
    }
}
