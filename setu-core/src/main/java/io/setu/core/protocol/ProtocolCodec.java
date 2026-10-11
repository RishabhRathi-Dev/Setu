package io.setu.core.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Binary Setu protocol v1 codec. Frame boundaries are provided by setu-server. */
public final class ProtocolCodec {
    public static final int MAGIC = 0x53455455; // SETU
    public static final int VERSION = 1;
    private static final int REQUEST = 0;
    private static final int RESPONSE = 1;
    private static final int MAX_STRING_BYTES = 4_096;
    private static final int MAX_FETCH_BYTES = 15 * 1024 * 1024;
    // Leave room under the 15 MiB frame limit for request metadata and per-record lengths.
    private static final int MAX_BATCH_BYTES = 14 * 1024 * 1024;
    private static final int MAX_RESPONSE_ITEMS = 10_000;

    private ProtocolCodec() { }

    public static byte[] encodeRequest(BrokerRequest request) throws IOException {
        validateRequest(request);
        int capacity = 32;
        if (request.command() == Command.PRODUCE_BATCH) {
            // Validated topic names are ASCII; the complete batch is already bounded to 14 MiB.
            capacity = 24 + request.topic().length();
            for (int i = 0; i < request.valueCount(); i++) capacity += Integer.BYTES + request.valueLengthAt(i);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(capacity);
        DataOutputStream out = new DataOutputStream(bytes);
        writeHeader(out, REQUEST, request.command(), request.correlationId());
        switch (request.command()) {
            case PRODUCE -> {
                writeString(out, request.topic());
                out.writeInt(request.partition());
                writeBytes(out, request.valueForEncoding());
            }
            case PRODUCE_BATCH -> {
                writeString(out, request.topic());
                out.writeInt(request.partition());
                out.writeInt(request.valueCount());
                for (int i = 0; i < request.valueCount(); i++) writeBytes(out, request.valueForEncoding(i));
            }
            case FETCH -> {
                writeString(out, request.topic());
                out.writeInt(request.partition());
                out.writeLong(request.offset());
                out.writeInt(request.maxBytes());
                if (request.groupId() != null) {
                    writeString(out, request.groupId());
                    writeString(out, request.memberId());
                }
            }
            case COMMIT_OFFSET -> {
                writeString(out, request.groupId());
                writeString(out, request.topic());
                out.writeInt(request.partition());
                out.writeLong(request.offset());
                if (request.memberId() != null) writeString(out, request.memberId());
            }
            case JOIN_GROUP -> {
                writeString(out, request.groupId());
                writeString(out, request.topic());
                writeString(out, request.memberId());
            }
            case LEAVE_GROUP, HEARTBEAT -> {
                writeString(out, request.groupId());
                writeString(out, request.memberId());
            }
            case CREATE_TOPIC -> {
                writeString(out, request.topic());
                out.writeInt(request.partitionCount());
            }
            case METADATA -> { }
        }
        return bytes.toByteArray();
    }

    public static BrokerRequest decodeRequest(byte[] payload) throws ProtocolException {
        int correlation = extractCorrelationId(payload);
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            Header header = readHeader(in, REQUEST);
            correlation = header.correlationId;
            BrokerRequest request = switch (header.command) {
                case PRODUCE -> new BrokerRequest(correlation, header.command, readString(in), in.readInt(),
                        -1, -1, readBytes(in), 0, null, null);
                case PRODUCE_BATCH -> decodeProduceBatch(in, correlation);
                case FETCH -> decodeFetch(in, correlation);
                case COMMIT_OFFSET -> decodeCommit(in, correlation);
                case JOIN_GROUP -> decodeJoin(in, correlation);
                case LEAVE_GROUP, HEARTBEAT -> new BrokerRequest(correlation, header.command, null, -1, -1, -1,
                        null, 0, readString(in), readString(in));
                case CREATE_TOPIC -> new BrokerRequest(correlation, header.command, readString(in), -1, -1, -1,
                        null, in.readInt(), null, null);
                case METADATA -> new BrokerRequest(correlation, header.command, null, -1, -1, -1,
                        null, 0, null, null);
            };
            if (in.available() != 0) throw bad("Trailing bytes in request", correlation);
            validateRequest(request);
            return request;
        } catch (ProtocolException e) {
            throw e;
        } catch (EOFException e) {
            throw bad("Truncated request", correlation);
        } catch (IOException | IllegalArgumentException e) {
            throw bad("Malformed request: " + e.getMessage(), correlation);
        }
    }

    private static BrokerRequest decodeCommit(DataInputStream in, int correlation) throws IOException {
        String group = readString(in);
        String topic = readString(in);
        int partition = in.readInt();
        long offset = in.readLong();
        String member = in.available() > 0 ? readString(in) : null;
        return new BrokerRequest(correlation, Command.COMMIT_OFFSET, topic, partition, offset,
                0, null, 0, group, member);
    }

    private static BrokerRequest decodeFetch(DataInputStream in, int correlation) throws IOException {
        String topic = readString(in);
        int partition = in.readInt();
        long offset = in.readLong();
        int maxBytes = in.readInt();
        String group = null;
        String member = null;
        if (in.available() > 0) {
            group = readString(in);
            member = readString(in);
        }
        return new BrokerRequest(correlation, Command.FETCH, topic, partition,
                offset, maxBytes, null, 0, group, member);
    }

    private static BrokerRequest decodeProduceBatch(DataInputStream in, int correlation) throws IOException {
        String topic = readString(in);
        int partition = in.readInt();
        int count = boundedCount(in.readInt());
        List<byte[]> values = new ArrayList<>(count);
        long bytes = 0;
        for (int i = 0; i < count; i++) {
            byte[] value = readBytes(in);
            bytes += value.length;
            if (bytes > MAX_BATCH_BYTES) throw bad("produce batch exceeds maximum bytes", correlation);
            values.add(value);
        }
        return new BrokerRequest(correlation, Command.PRODUCE_BATCH, topic, partition, -1,
                -1, null, 0, null, null, values);
    }

    private static BrokerRequest decodeJoin(DataInputStream in, int correlation) throws IOException {
        String group = readString(in);
        String topic = readString(in);
        String member = readString(in);
        return new BrokerRequest(correlation, Command.JOIN_GROUP, topic, -1, -1, -1,
                null, 0, group, member);
    }

    private static void validateRequest(BrokerRequest request) throws ProtocolException {
        switch (request.command()) {
            case PRODUCE -> {
                requireTopic(request.topic());
                if (request.partition() < 0) throw bad("partition must be non-negative", request.correlationId());
            }
            case PRODUCE_BATCH -> {
                requireTopic(request.topic());
                if (request.partition() < 0) throw bad("partition must be non-negative", request.correlationId());
                if (request.valueCount() == 0 || request.valueCount() > MAX_RESPONSE_ITEMS)
                    throw bad("batch must contain between 1 and " + MAX_RESPONSE_ITEMS + " records", request.correlationId());
                long bytes = 0;
                for (int i = 0; i < request.valueCount(); i++) {
                    bytes += request.valueLengthAt(i);
                    if (bytes > MAX_BATCH_BYTES) throw bad("produce batch exceeds maximum bytes", request.correlationId());
                }
            }
            case FETCH -> {
                requireTopic(request.topic());
                if (request.partition() < 0 || request.offset() < 0 || request.maxBytes() < 0
                        || request.maxBytes() > MAX_FETCH_BYTES)
                    throw bad("partition/offset must be non-negative and maxBytes must be 0.." + MAX_FETCH_BYTES,
                            request.correlationId());
                if ((request.groupId() == null) != (request.memberId() == null))
                    throw bad("groupId and memberId must both be provided for grouped fetch",
                            request.correlationId());
                if (request.groupId() != null) {
                    requireString(request.groupId(), "groupId", request.correlationId());
                    requireString(request.memberId(), "memberId", request.correlationId());
                }
            }
            case CREATE_TOPIC -> {
                requireTopic(request.topic());
                if (request.partitionCount() <= 0 || request.partitionCount() > 1_024)
                    throw bad("partitionCount must be between 1 and 1024", request.correlationId());
            }
            case COMMIT_OFFSET -> {
                requireTopic(request.topic());
                requireString(request.groupId(), "groupId", request.correlationId());
                if (request.partition() < 0 || request.offset() < 0)
                    throw bad("partition and offset must be non-negative", request.correlationId());
                if (request.memberId() != null)
                    requireString(request.memberId(), "memberId", request.correlationId());
            }
            case JOIN_GROUP -> {
                requireTopic(request.topic());
                requireString(request.groupId(), "groupId", request.correlationId());
                requireString(request.memberId(), "memberId", request.correlationId());
            }
            case LEAVE_GROUP, HEARTBEAT -> {
                requireString(request.groupId(), "groupId", request.correlationId());
                requireString(request.memberId(), "memberId", request.correlationId());
            }
            case METADATA -> { }
        }
    }

    private static void requireTopic(String topic) throws ProtocolException {
        requireString(topic, "topic", -1);
        if (topic.length() > 249 || topic.equals(".") || topic.equals("..")
                || !topic.matches("[A-Za-z0-9._-]+"))
            throw bad("topic name is invalid", -1);
    }

    private static void requireString(String value, String field, int correlation) throws ProtocolException {
        if (value == null || value.isBlank()) throw bad(field + " must not be blank", correlation);
    }

    public static byte[] encodeResponse(BrokerResponse response) throws IOException {
        int capacity = 32;
        if (response.errorCode() == ErrorCode.NONE && response.command() == Command.FETCH) {
            long required = 22L + response.errorMessage().getBytes(StandardCharsets.UTF_8).length;
            for (ProtocolRecord record : response.records()) required += 12L + record.valueLength();
            if (required > Integer.MAX_VALUE) throw new IOException("Fetch response is too large to encode");
            capacity = (int) required;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(capacity);
        DataOutputStream out = new DataOutputStream(bytes);
        writeHeader(out, RESPONSE, response.command(), response.correlationId());
        out.writeShort(response.errorCode().id());
        writeString(out, response.errorMessage());
        if (response.errorCode() != ErrorCode.NONE) return bytes.toByteArray();
        switch (response.command()) {
            case PRODUCE, PRODUCE_BATCH -> out.writeLong(response.offset());
            case FETCH -> {
                out.writeInt(response.records().size());
                for (ProtocolRecord record : response.records()) {
                    out.writeLong(record.offset());
                    writeBytes(out, record.valueForEncoding());
                }
            }
            case METADATA -> {
                out.writeInt(response.topics().size());
                for (Map.Entry<String, Integer> topic : response.topics().entrySet()) {
                    writeString(out, topic.getKey());
                    out.writeInt(topic.getValue());
                }
            }
            default -> { }
        }
        return bytes.toByteArray();
    }

    public static BrokerResponse decodeResponse(byte[] payload) throws ProtocolException {
        int correlation = extractCorrelationId(payload);
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            Header header = readHeader(in, RESPONSE);
            correlation = header.correlationId;
            ErrorCode errorCode = ErrorCode.fromId(in.readUnsignedShort());
            String message = readString(in);
            long offset = -1;
            List<ProtocolRecord> records = List.of();
            Map<String, Integer> topics = Map.of();
            if (errorCode == ErrorCode.NONE) {
                switch (header.command) {
                    case PRODUCE, PRODUCE_BATCH -> offset = in.readLong();
                    case FETCH -> {
                        int count = boundedCount(in.readInt());
                        List<ProtocolRecord> decoded = new ArrayList<>(count);
                        for (int i = 0; i < count; i++) decoded.add(new ProtocolRecord(in.readLong(), readBytes(in)));
                        records = List.copyOf(decoded);
                    }
                    case METADATA -> {
                        int count = boundedCount(in.readInt());
                        Map<String, Integer> decoded = new LinkedHashMap<>();
                        for (int i = 0; i < count; i++) decoded.put(readString(in), in.readInt());
                        topics = Map.copyOf(decoded);
                    }
                    default -> { }
                }
            }
            if (in.available() != 0) throw bad("Trailing bytes in response", correlation);
            return new BrokerResponse(correlation, header.command, errorCode, message, offset, records, topics);
        } catch (ProtocolException e) {
            throw e;
        } catch (EOFException e) {
            throw bad("Truncated response", correlation);
        } catch (IOException | IllegalArgumentException e) {
            throw bad("Malformed response: " + e.getMessage(), correlation);
        }
    }

    public static byte[] errorResponse(byte[] request, ErrorCode code, String message) {
        HeaderParts header = extractHeader(request);
        String safeMessage = message == null ? "" : message;
        while (safeMessage.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_BYTES)
            safeMessage = safeMessage.substring(0, safeMessage.length() - 1);
        return encodeUnchecked(BrokerResponse.error(header.correlationId, header.command, code, safeMessage));
    }

    private static byte[] encodeUnchecked(BrokerResponse response) {
        try { return encodeResponse(response); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void writeHeader(DataOutputStream out, int kind, Command command, int correlation) throws IOException {
        out.writeInt(MAGIC);
        out.writeByte(VERSION);
        out.writeByte(kind);
        out.writeShort(command.id());
        out.writeInt(correlation);
    }

    private static Header readHeader(DataInputStream in, int expectedKind) throws IOException {
        if (in.readInt() != MAGIC) throw bad("Invalid protocol magic", -1);
        int version = in.readUnsignedByte();
        if (version != VERSION) throw new ProtocolException(ErrorCode.UNSUPPORTED_VERSION,
                "Unsupported protocol version: " + version);
        if (in.readUnsignedByte() != expectedKind) throw bad("Unexpected message kind", -1);
        Command command = Command.fromId(in.readUnsignedShort());
        int correlation = in.readInt();
        if (correlation < 0) throw bad("correlationId must be non-negative", correlation);
        return new Header(command, correlation);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) throw new IOException("Required string field is null");
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_STRING_BYTES) throw new IOException("String field exceeds " + MAX_STRING_BYTES + " bytes");
        writeBytes(out, data);
    }

    private static String readString(DataInputStream in) throws IOException {
        byte[] bytes = readBytes(in, MAX_STRING_BYTES);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("String field is not valid UTF-8", malformed);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        return readBytes(in, 16 * 1024 * 1024);
    }

    private static byte[] readBytes(DataInputStream in, int limit) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > limit || length > in.available()) throw new IOException("Invalid byte field length: " + length);
        byte[] value = new byte[length];
        in.readFully(value);
        return value;
    }

    private static int boundedCount(int count) throws IOException {
        if (count < 0 || count > MAX_RESPONSE_ITEMS) throw new IOException("Invalid item count: " + count);
        return count;
    }

    private static int extractCorrelationId(byte[] payload) {
        HeaderParts header = extractHeader(payload);
        return header.correlationId;
    }

    private static HeaderParts extractHeader(byte[] payload) {
        if (payload == null || payload.length < 12) return new HeaderParts(Command.METADATA, 0);
        ByteBufferReader reader = new ByteBufferReader(payload);
        int magic = reader.intAt(0);
        int commandId = reader.unsignedShortAt(6);
        int correlation = reader.intAt(8);
        Command command = Command.METADATA;
        if (magic == MAGIC) {
            try { command = Command.fromId(commandId); }
            catch (ProtocolException ignored) { }
        }
        if (correlation < 0) correlation = 0;
        return new HeaderParts(command, correlation);
    }

    private static ProtocolException bad(String message, int correlation) {
        return new ProtocolException(ErrorCode.BAD_REQUEST, message, Math.max(correlation, 0));
    }

    private record Header(Command command, int correlationId) { }
    private record HeaderParts(Command command, int correlationId) { }

    private static final class ByteBufferReader {
        private final byte[] bytes;
        private ByteBufferReader(byte[] bytes) { this.bytes = bytes; }
        private int intAt(int i) { return (bytes[i] & 255) << 24 | (bytes[i + 1] & 255) << 16 | (bytes[i + 2] & 255) << 8 | (bytes[i + 3] & 255); }
        private int unsignedShortAt(int i) { return (bytes[i] & 255) << 8 | (bytes[i + 1] & 255); }
    }
}
