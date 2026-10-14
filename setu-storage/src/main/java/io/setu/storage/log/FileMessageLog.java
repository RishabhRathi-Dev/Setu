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
    private static final int MAX_RETAINED_APPEND_BYTES = 1024 * 1024;
    // Only append uses this scratch space, under the same monitor as the file/index.
    private ByteBuffer appendBuffer;
    // Uniform records have arithmetic positions. Materialize an index only when lengths vary.
    private long[] positions;
    private long uniformRecordBytes;
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
        reserveIndex(1, Integer.BYTES + (long) value.length);
        long position = endPosition;
        ByteBuffer record = appendBuffer(Math.addExact(Integer.BYTES, value.length))
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
        if (positions != null) positions[recordCount] = position;
        recordCount++;
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
        long uniformBytes = Integer.BYTES + (long) values.getFirst().length();
        for (MessageValue value : values) {
            Objects.requireNonNull(value, "value");
            if (value.length() > maxRecordBytes)
                throw new IOException("Record size " + value.length() + " exceeds maximum " + maxRecordBytes);
            encodedBytes += Integer.BYTES + (long) value.length();
            if (Integer.BYTES + (long) value.length() != uniformBytes) uniformBytes = -1;
            if (encodedBytes > Integer.MAX_VALUE) throw new IOException("Append batch is too large");
        }
        reserveIndex(values.size(), uniformBytes);
        long position = endPosition;
        long firstOffset = recordCount;
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        ByteBuffer batch = appendBuffer((int) encodedBytes);
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
        if (positions == null) {
            recordCount += values.size();
            endPosition += encodedBytes;
        } else for (MessageValue value : values) {
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
            long windowEnd = positionAt(offset);
            while (end < recordCount && records.size() + end - offset < MAX_RECORDS_PER_READ) {
                long recordEnd = end + 1 == recordCount ? endPosition : positionAt(end + 1);
                int length = Math.toIntExact(recordEnd - positionAt(end) - Integer.BYTES);
                if (bytesRead + length > maxBytes) break;
                if (end > offset && recordEnd - positionAt(offset) > READ_WINDOW_BYTES) break;
                bytesRead += length;
                windowEnd = recordEnd;
                end++;
            }
            if (end == offset) break;
            // Immutable file windows + encoded reply and record metadata, reserved before reading.
            admission.reserve(2L * bytesRead + 160L * (records.size() + end - offset) + 22);
            channel.position(positionAt(offset));
            MessageValue.Frame frame = MessageValue.Frame.readFrom(channel,
                    Math.toIntExact(windowEnd - positionAt(offset)));
            ByteBuffer data = frame.cursor();
            while (offset < end) {
                int length = data.getInt();
                long recordEnd = offset + 1 == recordCount ? endPosition : positionAt(offset + 1);
                if (length != recordEnd - positionAt(offset) - Integer.BYTES)
                    throw new IOException("Record length changed while reading offset " + offset);
                MessageValue value = frame.valueAt(data.position(), length);
                data.position(data.position() + length);
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

    /** Published file length; append rollback never advances it. */
    synchronized long sizeBytes() { return endPosition; }

    /** A rotated segment is read-only; do not retain its write scratch space. */
    synchronized void releaseAppendBuffer() { appendBuffer = null; }

    private ByteBuffer appendBuffer(int bytes) {
        // Oversized requests remain transient, rather than inflating every partition's heap.
        if (bytes > MAX_RETAINED_APPEND_BYTES) return ByteBuffer.allocate(bytes);
        if (appendBuffer == null || appendBuffer.capacity() < bytes) {
            int capacity = Math.max(4096, Integer.highestOneBit(Math.max(1, bytes - 1)) << 1);
            appendBuffer = ByteBuffer.allocate(capacity);
        }
        appendBuffer.clear();
        appendBuffer.limit(bytes);
        return appendBuffer;
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
            reserveIndex(1, Integer.BYTES + (long) length);
            if (positions != null) positions[recordCount] = position;
            recordCount++;
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
            appendBuffer = null;
            channel.close();
        }
    }

    private long positionAt(int offset) {
        return positions == null ? offset * uniformRecordBytes : positions[offset];
    }

    private void reserveIndex(int additional, long recordBytes) throws IOException {
        long required = recordCount + (long) additional;
        if (required > Integer.MAX_VALUE - 8) throw new IOException("Segment record index is full");
        if (positions == null) {
            if (recordBytes >= 0 && (recordCount == 0 || recordBytes == uniformRecordBytes)) {
                uniformRecordBytes = recordBytes;
                return;
            }
            positions = new long[(int) Math.max(1024, required)];
            for (int offset = 0; offset < recordCount; offset++) positions[offset] = offset * uniformRecordBytes;
        }
        if (required > positions.length)
            positions = Arrays.copyOf(positions, (int) Math.max(required,
                    Math.min(Integer.MAX_VALUE - 8L, positions.length * 2L)));
    }
}
