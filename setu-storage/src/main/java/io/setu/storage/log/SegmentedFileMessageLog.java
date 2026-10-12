package io.setu.storage.log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Append-only partition log split into files whose names contain their base offsets. */
public final class SegmentedFileMessageLog implements MessageLog {
    private static final Logger LOG = LoggerFactory.getLogger(SegmentedFileMessageLog.class);
    private static final int MAX_RECORDS_PER_READ = 10_000;
    private static final Pattern SEGMENT_NAME = Pattern.compile("([0-9]{12,})\\.log");
    private final Path directory;
    private final int maxRecordBytes;
    private final long maxSegmentBytes;
    private final List<Segment> segments = new ArrayList<>();
    private long firstOffset;
    private long nextOffset;
    private boolean closed;

    public SegmentedFileMessageLog(Path directory, int maxRecordBytes, long maxSegmentBytes) throws IOException {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (maxRecordBytes < 0) throw new IllegalArgumentException("maxRecordBytes must be non-negative");
        if (maxSegmentBytes <= 0) throw new IllegalArgumentException("maxSegmentBytes must be positive");
        this.maxRecordBytes = maxRecordBytes;
        this.maxSegmentBytes = maxSegmentBytes;
        Files.createDirectories(this.directory);
        recover();
        LOG.info("Partition log opened directory={} segments={} first_offset={} next_offset={} max_segment_bytes={}",
                this.directory, segments.size(), firstOffset, nextOffset, maxSegmentBytes);
    }

    @Override
    public synchronized long append(byte[] value) throws IOException {
        ensureOpen();
        Objects.requireNonNull(value, "value");
        if (value.length > maxRecordBytes)
            throw new IOException("Record size " + value.length + " exceeds maximum " + maxRecordBytes);
        Segment active = segments.getLast();
        long recordBytes = Integer.BYTES + (long) value.length;
        if (active.log.nextOffset() > 0 && Files.size(active.path) + recordBytes > maxSegmentBytes) {
            active = createSegment(nextOffset);
        }
        long localOffset = active.log.append(value);
        long offset = active.baseOffset + localOffset;
        if (offset != nextOffset) throw new IOException("Segment offset sequence is inconsistent");
        nextOffset++;
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
        for (MessageValue value : values) {
            Objects.requireNonNull(value, "value");
            if (value.length() > maxRecordBytes)
                throw new IOException("Record size " + value.length() + " exceeds maximum " + maxRecordBytes);
        }
        long firstOffset = nextOffset;
        int start = 0;
        while (start < values.size()) {
            Segment active = segments.getLast();
            long size = Files.size(active.path);
            int end = start;
            long batchBytes = 0;
            while (end < values.size()) {
                long recordBytes = Integer.BYTES + (long) values.get(end).length();
                if (end > start && size + batchBytes + recordBytes > maxSegmentBytes) break;
                if (end == start && active.log.nextOffset() > 0 && size + recordBytes > maxSegmentBytes) {
                    active = createSegment(nextOffset);
                    size = 0;
                }
                batchBytes += recordBytes;
                end++;
            }
            List<MessageValue> part = values.subList(start, end);
            long localFirst = active.log.appendValues(part);
            if (active.baseOffset + localFirst != nextOffset)
                throw new IOException("Segment offset sequence is inconsistent");
            nextOffset += part.size();
            start = end;
        }
        return firstOffset;
    }

    @Override
    public synchronized List<LogRecord> read(long offset, int maxBytes) throws IOException {
        return read(offset, maxBytes, ReadAdmission.UNLIMITED);
    }

    @Override
    public synchronized List<LogRecord> read(long offset, int maxBytes, ReadAdmission admission) throws IOException {
        ensureOpen();
        if (offset < 0 || maxBytes < 0)
            throw new IllegalArgumentException("offset and maxBytes must be non-negative");
        if (maxBytes == 0 || offset >= nextOffset) return List.of();
        long start = Math.max(offset, firstOffset);
        long bytesRead = 0;
        List<LogRecord> records = new ArrayList<>();
        for (int index = segmentIndex(start); index < segments.size(); index++) {
            Segment segment = segments.get(index);
            long segmentEnd = segment.baseOffset + segment.log.nextOffset();
            if (segmentEnd <= start) continue;
            long localStart = Math.max(0, start - segment.baseOffset);
            int remainingBytes = (int) Math.max(0, maxBytes - bytesRead);
            if (remainingBytes == 0) break;
            long priorBytes = 2L * bytesRead + 160L * records.size();
            List<LogRecord> part = segment.log.read(localStart, remainingBytes,
                    bytes -> admission.reserve(priorBytes + bytes));
            if (part.isEmpty()) break;
            for (LogRecord record : part) {
                if (records.size() >= MAX_RECORDS_PER_READ) return List.copyOf(records);
                records.add(new LogRecord(segment.baseOffset + record.offset(), record.payload()));
                bytesRead += record.valueLength();
            }
            start = segment.baseOffset + part.getLast().offset() + 1;
            if (start < segmentEnd || records.size() >= MAX_RECORDS_PER_READ || bytesRead >= maxBytes) break;
        }
        return List.copyOf(records);
    }

    @Override
    public synchronized long nextOffset() { return nextOffset; }

    @Override
    public synchronized long firstOffset() { return firstOffset; }

    private int segmentIndex(long offset) {
        int low = 0;
        int high = segments.size() - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (segments.get(middle).baseOffset <= offset) low = middle;
            else high = middle - 1;
        }
        return low;
    }

    private void recover() throws IOException {
        List<NamedSegment> files;
        try (var entries = Files.list(directory)) {
            files = entries.filter(Files::isRegularFile)
                    .map(path -> namedSegment(path))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingLong(NamedSegment::baseOffset))
                    .toList();
        }
        if (files.isEmpty()) {
            firstOffset = 0;
            nextOffset = 0;
            createSegment(0);
            return;
        }

        firstOffset = files.getFirst().baseOffset;
        nextOffset = firstOffset;
        try {
            for (NamedSegment file : files) {
                if (file.baseOffset != nextOffset)
                    throw new IOException("Segment offset gap or overlap at " + file.path.getFileName());
                FileMessageLog log = new FileMessageLog(file.path, maxRecordBytes);
                segments.add(new Segment(file.baseOffset, file.path, log));
                nextOffset += log.nextOffset();
            }
        } catch (IOException | RuntimeException failure) {
            closeSegments(failure);
            throw failure;
        }
    }

    private NamedSegment namedSegment(Path path) {
        Matcher matcher = SEGMENT_NAME.matcher(path.getFileName().toString());
        if (!matcher.matches()) return null;
        try { return new NamedSegment(Long.parseLong(matcher.group(1)), path); }
        catch (NumberFormatException malformed) { return null; }
    }

    private Segment createSegment(long baseOffset) throws IOException {
        Path path = directory.resolve(String.format(java.util.Locale.ROOT, "%012d.log", baseOffset));
        FileMessageLog log = new FileMessageLog(path, maxRecordBytes);
        Segment segment = new Segment(baseOffset, path, log);
        segments.add(segment);
        LOG.info("Segment created path={} base_offset={} segment_count={} max_segment_bytes={}",
                path, baseOffset, segments.size(), maxSegmentBytes);
        return segment;
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Message log is closed");
    }

    private void closeSegments(Throwable original) {
        for (Segment segment : segments) {
            try { segment.log.close(); }
            catch (IOException closeFailure) { original.addSuppressed(closeFailure); }
        }
        segments.clear();
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (Segment segment : segments) {
            try { segment.log.close(); }
            catch (IOException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw failure;
    }

    private record NamedSegment(long baseOffset, Path path) { }
    private record Segment(long baseOffset, Path path, FileMessageLog log) { }
}
