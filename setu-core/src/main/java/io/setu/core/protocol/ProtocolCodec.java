package io.setu.core.protocol;

import io.setu.storage.log.MessageValue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.BufferUnderflowException;
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

    /**
     * Receive buffer for serialized client exchanges. Control replies decode to independent
     * objects; fetch replies keep private frames because their payload slices can escape.
     */
    public static final class ResponseReader {
        private static final int MAX_RETAINED_BYTES = 64 * 1024;
        private byte[] buffer;
        private int frameLength;

        public BrokerResponse readFrom(DataInputStream input, int maxFrameSize, Command expectedCommand) throws IOException {
            java.util.Objects.requireNonNull(expectedCommand, "expectedCommand");
            int length = FrameCodec.readLength(input, maxFrameSize);
            frameLength = length;
            if (length < 0) return null;
            if (expectedCommand == Command.FETCH || length > MAX_RETAINED_BYTES)
                return decodeResponse(MessageValue.Frame.readFrom(input, length));
            if (buffer == null || buffer.length < length)
                buffer = new byte[Math.min(MAX_RETAINED_BYTES, Math.max(256, length))];
            input.readFully(buffer, 0, length);
            // Bound the cursor to this reply, excluding bytes left by a longer previous reply.
            // The copying decoder also handles an unexpected FETCH safely before the client
            // rejects its command: none of its records can reference the scratch buffer.
            ByteBuffer cursor = ByteBuffer.wrap(buffer, 0, length);
            int correlation = length >= 12 ? cursor.getInt(8) : -1;
            return decodeResponse(cursor, correlation, null);
        }

        public int frameLength() { return frameLength; }
        public void clear() { buffer = null; }
    }

    /** Copies caller payloads directly into their final wire frame. */
    public static byte[] encodeProduceBatch(int correlation, String topic, int partition, List<byte[]> values) throws IOException {
        return encodeProduceBatch(correlation, topic, partition, values, null);
    }

    /**
     * Scratch encoder for serialized client exchanges. The returned frame is valid only until
     * the next encode; callers must finish writing it before reusing this encoder.
     */
    public static final class ProduceBatchEncoder {
        private static final int MAX_RETAINED_BYTES = 1024 * 1024;
        private byte[] buffer;

        public byte[] encode(int correlation, String topic, int partition, List<byte[]> values) throws IOException {
            return encodeProduceBatch(correlation, topic, partition, values, this);
        }

        private byte[] allocate(int size) {
            if (size > MAX_RETAINED_BYTES) return new byte[size];
            // Exact length keeps framing honest when batch size or topic length changes.
            if (buffer == null || buffer.length != size) buffer = new byte[size];
            return buffer;
        }

        public void clear() { buffer = null; }
    }

    private static byte[] encodeProduceBatch(int correlation, String topic, int partition,
                                             List<byte[]> values, ProduceBatchEncoder encoder) throws IOException {
        requireTopic(topic);
        if (correlation < 0 || partition < 0) throw bad("correlation and partition must be non-negative", correlation);
        List<byte[]> snapshot = List.copyOf(values);
        if (snapshot.isEmpty() || snapshot.size() > MAX_RESPONSE_ITEMS)
            throw bad("batch must contain between 1 and " + MAX_RESPONSE_ITEMS + " records", correlation);
        long payloadBytes = 0;
        for (byte[] value : snapshot) payloadBytes += value.length;
        if (payloadBytes > MAX_BATCH_BYTES) throw bad("produce batch exceeds maximum bytes", correlation);
        byte[] name = topic.getBytes(StandardCharsets.UTF_8);
        int size = Math.toIntExact(24L + name.length + 4L * snapshot.size() + payloadBytes);
        ByteBuffer out = ByteBuffer.wrap(encoder == null ? new byte[size] : encoder.allocate(size));
        out.putInt(MAGIC).put((byte) VERSION).put((byte) REQUEST).putShort((short) Command.PRODUCE_BATCH.id()).putInt(correlation);
        out.putInt(name.length).put(name).putInt(partition).putInt(snapshot.size());
        for (byte[] value : snapshot) out.putInt(value.length).put(value);
        return out.array();
    }

    public static byte[] encodeRequest(BrokerRequest request) throws IOException {
        validateRequest(request);
        int capacity = 32;
        if (request.command() == Command.PRODUCE) capacity = 24 + request.topic().length() + request.valueLength();
        if (request.command() == Command.PRODUCE_BATCH) {
            // Validated topic names are ASCII; the complete batch is already bounded to 14 MiB.
            capacity = 24 + request.topic().length();
            for (int i = 0; i < request.valueCount(); i++) capacity += Integer.BYTES + request.valueLengthAt(i);
        }
        EncodingBuffer bytes = new EncodingBuffer(capacity);
        DataOutputStream out = new DataOutputStream(bytes);
        writeHeader(out, REQUEST, request.command(), request.correlationId());
        switch (request.command()) {
            case PRODUCE -> {
                writeString(out, request.topic());
                out.writeInt(request.partition());
                writeValue(out, request.payload());
            }
            case PRODUCE_BATCH -> {
                writeString(out, request.topic());
                out.writeInt(request.partition());
                out.writeInt(request.valueCount());
                for (MessageValue value : request.payloads()) writeValue(out, value);
            }
            case FETCH -> {
                writeString(out, request.topic());
                out.writeInt(request.partition());
                out.writeLong(request.offset());
                out.writeInt(request.maxBytes());
                if (request.groupId() != null || request.waitMillis() > 0) {
                    writeString(out, request.groupId() == null ? "" : request.groupId());
                    writeString(out, request.memberId() == null ? "" : request.memberId());
                }
                if (request.waitMillis() > 0) out.writeInt(request.waitMillis());
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
        return bytes.finish();
    }

    public static BrokerRequest decodeRequest(byte[] payload) throws ProtocolException {
        return decodeRequest(ByteBuffer.wrap(payload), extractCorrelationId(payload), null);
    }

    /** Immutable transport-owned frames can be retained by storage without copying payloads. */
    public static BrokerRequest decodeRequest(MessageValue.Frame frame) throws ProtocolException {
        ByteBuffer cursor = frame.cursor();
        int correlation = cursor.remaining() >= 12 ? cursor.getInt(8) : -1;
        return decodeRequest(cursor, correlation, frame);
    }

    private static BrokerRequest decodeRequest(ByteBuffer in, int correlation, MessageValue.Frame frame) throws ProtocolException {
        try {
            Header header = readHeader(in, REQUEST);
            correlation = header.correlationId;
            BrokerRequest request = switch (header.command) {
                case PRODUCE -> new BrokerRequest(correlation, header.command, readString(in, frame), in.getInt(),
                        -1, -1, readValue(in, frame), 0, null, null, List.of(), 0);
                case PRODUCE_BATCH -> decodeProduceBatch(in, correlation, frame);
                case FETCH -> decodeFetch(in, correlation);
                case COMMIT_OFFSET -> decodeCommit(in, correlation);
                case JOIN_GROUP -> decodeJoin(in, correlation);
                case LEAVE_GROUP, HEARTBEAT -> new BrokerRequest(correlation, header.command, null, -1, -1, -1,
                        null, 0, readString(in), readString(in));
                case CREATE_TOPIC -> new BrokerRequest(correlation, header.command, readString(in), -1, -1, -1,
                        null, in.getInt(), null, null);
                case METADATA -> new BrokerRequest(correlation, header.command, null, -1, -1, -1,
                        null, 0, null, null);
            };
            if (in.hasRemaining()) throw bad("Trailing bytes in request", correlation);
            validateRequest(request);
            return request;
        } catch (ProtocolException e) {
            throw e;
        } catch (BufferBudget.Exhausted e) {
            throw new ProtocolException(ErrorCode.QUOTA_EXCEEDED, e.getMessage(), correlation);
        } catch (EOFException | BufferUnderflowException e) {
            throw bad("Truncated request", correlation);
        } catch (IOException | IllegalArgumentException e) {
            throw bad("Malformed request: " + e.getMessage(), correlation);
        }
    }

    private static BrokerRequest decodeCommit(ByteBuffer in, int correlation) throws IOException {
        String group = readString(in);
        String topic = readString(in);
        int partition = in.getInt();
        long offset = in.getLong();
        String member = in.hasRemaining() ? readString(in) : null;
        return new BrokerRequest(correlation, Command.COMMIT_OFFSET, topic, partition, offset,
                0, null, 0, group, member);
    }

    private static BrokerRequest decodeFetch(ByteBuffer in, int correlation) throws IOException {
        String topic = readString(in);
        int partition = in.getInt();
        long offset = in.getLong();
        int maxBytes = in.getInt();
        String group = null;
        String member = null;
        if (in.hasRemaining()) {
            group = readString(in);
            member = readString(in);
        }
        int wait = in.hasRemaining() ? in.getInt() : 0;
        if (wait > 0 && "".equals(group) && "".equals(member)) { group = null; member = null; }
        return new BrokerRequest(correlation, Command.FETCH, topic, partition,
                offset, maxBytes, MessageValue.empty(), 0, group, member, List.of(), wait);
    }

    private static BrokerRequest decodeProduceBatch(ByteBuffer in, int correlation, MessageValue.Frame frame) throws IOException {
        String topic = readString(in, frame);
        int partition = in.getInt();
        int count = boundedCount(in.getInt());
        RequestBuffers.reserveObjects(64L * count);
        List<MessageValue> values = new ArrayList<>(count);
        long bytes = 0;
        for (int i = 0; i < count; i++) {
            MessageValue value = readValue(in, frame);
            bytes += value.length();
            if (bytes > MAX_BATCH_BYTES) throw bad("produce batch exceeds maximum bytes", correlation);
            values.add(value);
        }
        return new BrokerRequest(correlation, Command.PRODUCE_BATCH, topic, partition, -1,
                -1, MessageValue.empty(), 0, null, null, values, 0);
    }

    private static BrokerRequest decodeJoin(ByteBuffer in, int correlation) throws IOException {
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
                || !validTopicCharacters(topic))
            throw bad("topic name is invalid", -1);
    }

    private static boolean validTopicCharacters(String topic) {
        for (int i = 0; i < topic.length(); i++) {
            char c = topic.charAt(i);
            if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                    || c == '.' || c == '_' || c == '-')) return false;
        }
        return true;
    }

    private static void requireString(String value, String field, int correlation) throws ProtocolException {
        if (value == null || value.isBlank()) throw bad(field + " must not be blank", correlation);
    }

    public static byte[] encodeResponse(BrokerResponse response) throws IOException {
        if (response.errorCode() == ErrorCode.NONE && response.command() == Command.FETCH)
            return encodeFetchResponse(response);
        long size = 18L + response.errorMessage().getBytes(StandardCharsets.UTF_8).length;
        if (response.errorCode() == ErrorCode.NONE) {
            if (response.command() == Command.PRODUCE || response.command() == Command.PRODUCE_BATCH) size += 8;
            if (response.command() == Command.METADATA) {
                size += 4;
                for (String topic : response.topics().keySet()) size += 8L + topic.getBytes(StandardCharsets.UTF_8).length;
            }
        }
        ResponseBuffers.reserve(size);
        EncodingBuffer bytes = new EncodingBuffer(Math.toIntExact(size));
        DataOutputStream out = new DataOutputStream(bytes);
        writeHeader(out, RESPONSE, response.command(), response.correlationId());
        out.writeShort(response.errorCode().id());
        writeString(out, response.errorMessage());
        if (response.errorCode() != ErrorCode.NONE) return bytes.finish();
        switch (response.command()) {
            case PRODUCE, PRODUCE_BATCH -> out.writeLong(response.offset());
            case METADATA -> {
                out.writeInt(response.topics().size());
                for (Map.Entry<String, Integer> topic : response.topics().entrySet()) {
                    writeString(out, topic.getKey());
                    out.writeInt(topic.getValue());
                }
            }
            default -> { }
        }
        return bytes.finish();
    }

    private static byte[] encodeFetchResponse(BrokerResponse response) throws IOException {
        byte[] message = response.errorMessage().getBytes(StandardCharsets.UTF_8);
        if (message.length > MAX_STRING_BYTES)
            throw new IOException("String field exceeds " + MAX_STRING_BYTES + " bytes");
        long required = 22L + message.length;
        for (ProtocolRecord record : response.records()) required += 12L + record.valueLength();
        if (required > Integer.MAX_VALUE) throw new IOException("Fetch response is too large to encode");
        ResponseBuffers.reserve(required);
        ByteBuffer output = ByteBuffer.allocate((int) required);
        output.putInt(MAGIC).put((byte) VERSION).put((byte) RESPONSE)
                .putShort((short) response.command().id()).putInt(response.correlationId())
                .putShort((short) response.errorCode().id()).putInt(message.length).put(message)
                .putInt(response.records().size());
        for (ProtocolRecord record : response.records()) {
            output.putLong(record.offset()).putInt(record.valueLength());
            record.payload().copyTo(output);
        }
        return output.array();
    }

    public static BrokerResponse decodeResponse(byte[] payload) throws ProtocolException {
        return decodeResponse(ByteBuffer.wrap(payload), extractCorrelationId(payload), null);
    }

    /** Decodes immutable slices; retaining a record also retains its response frame. */
    public static BrokerResponse decodeResponse(MessageValue.Frame frame) throws ProtocolException {
        ByteBuffer cursor = frame.cursor();
        int correlation = cursor.remaining() >= 12 ? cursor.getInt(8) : -1;
        return decodeResponse(cursor, correlation, frame);
    }

    private static BrokerResponse decodeResponse(ByteBuffer in, int correlation, MessageValue.Frame frame) throws ProtocolException {
        try {
            // One cursor for the whole frame avoids per-record wrappers and synchronized stream reads.
            Header header = readHeader(in, RESPONSE);
            correlation = header.correlationId;
            ErrorCode errorCode = ErrorCode.fromId(Short.toUnsignedInt(in.getShort()));
            String message = readString(in);
            long offset = -1;
            List<ProtocolRecord> records = List.of();
            Map<String, Integer> topics = Map.of();
            if (errorCode == ErrorCode.NONE) {
                switch (header.command) {
                    case PRODUCE, PRODUCE_BATCH -> offset = in.getLong();
                    case FETCH -> {
                        int count = boundedCount(in.getInt());
                        if (count > in.remaining() / 12) throw new EOFException("Truncated record headers");
                        List<ProtocolRecord> decoded = new ArrayList<>(count);
                        for (int i = 0; i < count; i++) {
                            long recordOffset = in.getLong();
                            int length = in.getInt();
                            if (length < 0 || length > 16 * 1024 * 1024 || length > in.remaining())
                                throw new IOException("Invalid byte field length: " + length);
                            MessageValue value;
                            if (frame == null) value = MessageValue.readFrom(in, length);
                            else {
                                value = frame.valueAt(in.position(), length);
                                in.position(in.position() + length);
                            }
                            decoded.add(new ProtocolRecord(recordOffset, value));
                        }
                        records = decoded; // BrokerResponse takes the immutable snapshot once.
                    }
                    case METADATA -> {
                        int count = boundedCount(in.getInt());
                        Map<String, Integer> decoded = new LinkedHashMap<>();
                        for (int i = 0; i < count; i++) decoded.put(readString(in), in.getInt());
                        topics = decoded;
                    }
                    default -> { }
                }
            }
            if (in.hasRemaining()) throw bad("Trailing bytes in response", correlation);
            return new BrokerResponse(correlation, header.command, errorCode, message, offset, records, topics);
        } catch (ProtocolException e) {
            throw e;
        } catch (EOFException | BufferUnderflowException e) {
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

    public static byte[] errorResponse(MessageValue.Frame request, ErrorCode code, String message) {
        // Error paths are cold; retain the existing malformed-header handling exactly.
        return errorResponse(request.copy(), code, message);
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

    private static Header readHeader(ByteBuffer in, int expectedKind) throws IOException {
        if (in.getInt() != MAGIC) throw bad("Invalid protocol magic", -1);
        int version = Byte.toUnsignedInt(in.get());
        if (version != VERSION) throw new ProtocolException(ErrorCode.UNSUPPORTED_VERSION,
                "Unsupported protocol version: " + version);
        if (Byte.toUnsignedInt(in.get()) != expectedKind) throw bad("Unexpected message kind", -1);
        Command command = Command.fromId(Short.toUnsignedInt(in.getShort()));
        int correlation = in.getInt();
        if (correlation < 0) throw bad("correlationId must be non-negative", correlation);
        return new Header(command, correlation);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) throw new IOException("Required string field is null");
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_STRING_BYTES) throw new IOException("String field exceeds " + MAX_STRING_BYTES + " bytes");
        writeBytes(out, data);
    }

    private static void writeValue(DataOutputStream out, MessageValue value) throws IOException {
        out.writeInt(value.length());
        value.writeTo(out);
    }

    private static MessageValue readValue(ByteBuffer in, MessageValue.Frame frame) throws IOException {
        int length = in.getInt();
        if (length < 0 || length > 16 * 1024 * 1024 || length > in.remaining())
            throw new IOException("Invalid byte field length: " + length);
        if (frame == null) return MessageValue.readFrom(in, length);
        MessageValue value = frame.valueAt(in.position(), length);
        in.position(in.position() + length);
        return value;
    }
    private static String readString(ByteBuffer in) throws IOException {
        return readString(in, null);
    }

    private static String readString(ByteBuffer in, MessageValue.Frame frame) throws IOException {
        int length = in.getInt();
        if (length < 0 || length > MAX_STRING_BYTES || length > in.remaining())
            throw new IOException("Invalid byte field length: " + length);
        if (length == 0) return "";
        // Topics and most group/member identifiers are ASCII. Decode them directly while
        // retaining the strict UTF-8 decoder for non-ASCII text and malformed sequences.
        int start = in.position();
        boolean ascii = true;
        for (int i = 0; i < length; i++) if (in.get(start + i) < 0) { ascii = false; break; }
        if (ascii && (in.hasArray() || frame != null)) {
            String value = frame != null ? frame.asciiAt(start, length)
                    : new String(in.array(), in.arrayOffset() + start, length, StandardCharsets.US_ASCII);
            in.position(start + length);
            return value;
        }
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(in.slice(in.position(), length)).toString();
            in.position(in.position() + length);
            return value;
        } catch (CharacterCodingException malformed) {
            throw new IOException("String field is not valid UTF-8", malformed);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
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

    private static final class EncodingBuffer extends ByteArrayOutputStream {
        private EncodingBuffer(int capacity) { super(capacity); }

        // Exact-sized batch encodings transfer their buffer; other commands return a trimmed copy.
        // The stream is local to the encoder and is never used after this call.
        private byte[] finish() { return count == buf.length ? buf : toByteArray(); }
    }
    private record HeaderParts(Command command, int correlationId) { }

    private static final class ByteBufferReader {
        private final byte[] bytes;
        private ByteBufferReader(byte[] bytes) { this.bytes = bytes; }
        private int intAt(int i) { return (bytes[i] & 255) << 24 | (bytes[i + 1] & 255) << 16 | (bytes[i + 2] & 255) << 8 | (bytes[i + 3] & 255); }
        private int unsignedShortAt(int i) { return (bytes[i] & 255) << 8 | (bytes[i + 1] & 255); }
    }
}
