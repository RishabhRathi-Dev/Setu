package io.setu.core.protocol;

import io.setu.storage.log.MessageFrame;
import io.setu.storage.log.MessageValue;
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

/** Binary Setu protocol codec. Every request carries trace-context fields. */
public final class ProtocolCodec {
    public static final int MAGIC = 0x53455455; // SETU
    static final int HEADER_BYTES = 11;
    private static final int COMMAND_OFFSET = 5;
    static final int CORRELATION_OFFSET = 7;
    private static final int REQUEST = 0;
    private static final int RESPONSE = 1;
    private static final int MAX_STRING_BYTES = 4_096;
    private static final int MAX_FETCH_BYTES = 15 * 1024 * 1024;
    // Leave room under the 15 MiB frame limit for request metadata and per-record lengths.
    private static final int MAX_BATCH_BYTES = 14 * 1024 * 1024;
    private static final int MAX_RESPONSE_ITEMS = 10_000;

    private ProtocolCodec() { }

    /** Copies caller payloads directly into their final wire frame. */
    public static byte[] encodeProduceBatch(int correlation, String topic, int partition, List<byte[]> values) throws IOException {
        return encodeProduceBatch(correlation, topic, partition, values, null, TraceContext.NONE);
    }

    static byte[] encodeProduceBatch(int correlation, String topic, int partition,
                                             List<byte[]> values, ProduceBatchEncoder encoder, TraceContext traceContext) throws IOException {
        requireTopic(topic);
        if (correlation < 0 || partition < 0) throw bad("correlation and partition must be non-negative", correlation);
        List<byte[]> snapshot = List.copyOf(values);
        if (snapshot.isEmpty() || snapshot.size() > MAX_RESPONSE_ITEMS)
            throw bad("batch must contain between 1 and " + MAX_RESPONSE_ITEMS + " records", correlation);
        long payloadBytes = 0;
        for (byte[] value : snapshot) payloadBytes += value.length;
        if (payloadBytes > MAX_BATCH_BYTES) throw bad("produce batch exceeds maximum bytes", correlation);
        byte[] name = topic.getBytes(StandardCharsets.UTF_8);
        int traceBytes = 8 + traceContext.traceparent().length() + traceContext.tracestate().length();
        int size = Math.toIntExact(HEADER_BYTES + 12L + name.length + 4L * snapshot.size() + payloadBytes + traceBytes);
        ByteBuffer out = ByteBuffer.wrap(encoder == null ? new byte[size] : encoder.allocate(size));
        out.putInt(MAGIC).put((byte) REQUEST).putShort((short) Command.PRODUCE_BATCH.id()).putInt(correlation);
        byte[] parent = traceContext.traceparent().getBytes(StandardCharsets.US_ASCII);
        byte[] state = traceContext.tracestate().getBytes(StandardCharsets.US_ASCII);
        out.putInt(parent.length).put(parent).putInt(state.length).put(state);
        out.putInt(name.length).put(name).putInt(partition).putInt(snapshot.size());
        for (byte[] value : snapshot) out.putInt(value.length).put(value);
        return out.array();
    }

    public static byte[] encodeRequest(BrokerRequest request) throws IOException {
        validateRequest(request);
        int capacity = 32;
        if (request.command() == Command.PRODUCE) capacity = HEADER_BYTES + 12 + request.topic().length() + request.valueLength();
        if (request.command() == Command.PRODUCE_BATCH || request.command() == Command.PRODUCE_IDEMPOTENT
                || request.command() == Command.TRANSACT || request.command() == Command.PRODUCE_CONFIGURED) {
            // Validated topic names are ASCII; the complete batch is already bounded to 14 MiB.
            capacity = HEADER_BYTES + 12 + request.topic().length();
            for (int i = 0; i < request.valueCount(); i++) capacity += Integer.BYTES + request.valueLengthAt(i);
            if (request.command() == Command.PRODUCE_CONFIGURED) capacity++;
            if (request.command() == Command.PRODUCE_IDEMPOTENT || request.command() == Command.TRANSACT)
                capacity += 20 + request.options().producer().id().getBytes(StandardCharsets.UTF_8).length;
            if (request.command() == Command.TRANSACT) {
                ConsumerCommit commit = request.options().commit();
                capacity += 32 + commit.groupId().getBytes(StandardCharsets.UTF_8).length
                        + commit.memberId().getBytes(StandardCharsets.UTF_8).length
                        + commit.topic().length();
            }
        }
        capacity += 8 + request.traceContext().traceparent().length() + request.traceContext().tracestate().length();
        ProtocolEncodingBuffer bytes = new ProtocolEncodingBuffer(capacity);
        DataOutputStream out = new DataOutputStream(bytes);
        writeHeader(out, REQUEST, request.command(), request.correlationId());
        writeString(out, request.traceContext().traceparent());
        writeString(out, request.traceContext().tracestate());
        switch (request.command()) {
            case PRODUCE -> {
                writeString(out, request.topic());
                out.writeInt(request.partition());
                writeValue(out, request.payload());
            }
            case PRODUCE_BATCH, PRODUCE_IDEMPOTENT, TRANSACT, PRODUCE_CONFIGURED -> {
                writeString(out, request.topic());
                out.writeInt(request.partition());
                out.writeInt(request.valueCount());
                for (MessageValue value : request.payloads()) writeValue(out, value);
                if (request.command() == Command.PRODUCE_CONFIGURED)
                    out.writeByte(request.options().acknowledgementMode().ordinal());
                if (request.command() == Command.PRODUCE_IDEMPOTENT || request.command() == Command.TRANSACT) {
                    ProducerIdentity producer = request.options().producer();
                    writeString(out, producer.id());
                    out.writeLong(producer.epoch());
                    out.writeLong(producer.sequence());
                }
                if (request.command() == Command.TRANSACT) {
                    ConsumerCommit commit = request.options().commit();
                    writeString(out, commit.groupId());
                    writeString(out, commit.memberId());
                    writeString(out, commit.topic());
                    out.writeInt(commit.partition());
                    out.writeLong(commit.nextOffset());
                    out.writeLong(commit.generation());
                }
            }
            case FETCH -> {
                writeString(out, request.topic());
                out.writeInt(request.partition());
                out.writeLong(request.offset());
                out.writeInt(request.maxBytes());
                if (request.groupId() != null || request.waitMillis() > 0 || request.options().generation() >= 0) {
                    writeString(out, request.groupId() == null ? "" : request.groupId());
                    writeString(out, request.memberId() == null ? "" : request.memberId());
                }
                if (request.waitMillis() > 0 || request.options().generation() >= 0) out.writeInt(request.waitMillis());
                if (request.options().generation() >= 0) out.writeLong(request.options().generation());
            }
            case COMMIT_OFFSET, FETCH_OFFSET -> {
                writeString(out, request.groupId());
                writeString(out, request.topic());
                out.writeInt(request.partition());
                out.writeLong(request.offset());
                if (request.memberId() != null) writeString(out, request.memberId());
                if (request.options().generation() >= 0) out.writeLong(request.options().generation());
            }
            case JOIN_GROUP, JOIN_MANAGED_GROUP, GROUP_ASSIGNMENT -> {
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
    public static BrokerRequest decodeRequest(MessageFrame frame) throws ProtocolException {
        ByteBuffer cursor = frame.cursor();
        int correlation = cursor.remaining() >= HEADER_BYTES ? cursor.getInt(CORRELATION_OFFSET) : -1;
        return decodeRequest(cursor, correlation, frame);
    }

    private static BrokerRequest decodeRequest(ByteBuffer in, int correlation, MessageFrame frame) throws ProtocolException {
        try {
            ProtocolHeader header = readHeader(in, REQUEST);
            correlation = header.correlationId();
            TraceContext trace = new TraceContext(readString(in, null, TraceContext.MAX_FIELD_BYTES),
                    readString(in, null, TraceContext.MAX_FIELD_BYTES));
            BrokerRequest request = switch (header.command()) {
                case PRODUCE -> new BrokerRequest(correlation, header.command(), readString(in, frame), in.getInt(),
                        -1, -1, readValue(in, frame), 0, null, null, List.of(), 0);
                case PRODUCE_BATCH, PRODUCE_IDEMPOTENT, TRANSACT, PRODUCE_CONFIGURED ->
                        decodeProduceBatch(in, correlation, frame, header.command());
                case FETCH -> decodeFetch(in, correlation);
                case COMMIT_OFFSET, FETCH_OFFSET -> decodeCommit(in, correlation, header.command());
                case JOIN_GROUP, JOIN_MANAGED_GROUP, GROUP_ASSIGNMENT -> decodeJoin(in, correlation, header.command());
                case LEAVE_GROUP, HEARTBEAT -> new BrokerRequest(correlation, header.command(), null, -1, -1, -1,
                        null, 0, readString(in), readString(in));
                case CREATE_TOPIC -> new BrokerRequest(correlation, header.command(), readString(in), -1, -1, -1,
                        null, in.getInt(), null, null);
                case METADATA -> new BrokerRequest(correlation, header.command(), null, -1, -1, -1,
                        null, 0, null, null);
            };
            if (in.hasRemaining()) throw bad("Trailing bytes in request", correlation);
            validateRequest(request);
            return trace.isEmpty() ? request : request.withTraceContext(trace);
        } catch (ProtocolException e) {
            throw e;
        } catch (BufferBudgetExceededException e) {
            throw new ProtocolException(ErrorCode.QUOTA_EXCEEDED, e.getMessage(), correlation);
        } catch (EOFException | BufferUnderflowException e) {
            throw bad("Truncated request", correlation);
        } catch (IOException | IllegalArgumentException e) {
            throw bad("Malformed request: " + e.getMessage(), correlation);
        }
    }

    private static BrokerRequest decodeCommit(ByteBuffer in, int correlation, Command command) throws IOException {
        String group = readString(in);
        String topic = readString(in);
        int partition = in.getInt();
        long offset = in.getLong();
        String member = in.hasRemaining() ? readString(in) : null;
        long generation = in.hasRemaining() ? in.getLong() : -1;
        return new BrokerRequest(correlation, command, topic, partition, offset,
                0, null, 0, group, member).withGeneration(generation);
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
        long generation = in.hasRemaining() ? in.getLong() : -1;
        if (wait > 0 && "".equals(group) && "".equals(member)) { group = null; member = null; }
        return new BrokerRequest(correlation, Command.FETCH, topic, partition,
                offset, maxBytes, MessageValue.empty(), 0, group, member, List.of(), wait).withGeneration(generation);
    }

    private static BrokerRequest decodeProduceBatch(ByteBuffer in, int correlation, MessageFrame frame, Command command) throws IOException {
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
        ProducerIdentity producer = null;
        ConsumerCommit commit = null;
        AcknowledgementMode mode = AcknowledgementMode.DURABLE;
        if (command == Command.PRODUCE_CONFIGURED) {
            int id = Byte.toUnsignedInt(in.get());
            if (id >= AcknowledgementMode.values().length) throw bad("Unknown acknowledgement mode", correlation);
            mode = AcknowledgementMode.values()[id];
        }
        if (command == Command.PRODUCE_IDEMPOTENT || command == Command.TRANSACT)
            producer = new ProducerIdentity(readString(in), in.getLong(), in.getLong());
        if (command == Command.TRANSACT)
            commit = new ConsumerCommit(readString(in), readString(in), readString(in), in.getInt(), in.getLong(), in.getLong());
        return new BrokerRequest(correlation, command, topic, partition, -1,
                -1, MessageValue.empty(), 0, null, null, values, 0,
                new RequestOptions(producer, commit, mode, -1));
    }

    private static BrokerRequest decodeJoin(ByteBuffer in, int correlation, Command command) throws IOException {
        String group = readString(in);
        String topic = readString(in);
        String member = readString(in);
        return new BrokerRequest(correlation, command, topic, -1, -1, -1,
                null, 0, group, member);
    }

    private static void validateRequest(BrokerRequest request) throws ProtocolException {
        switch (request.command()) {
            case PRODUCE -> {
                requireTopic(request.topic());
                if (request.partition() < 0) throw bad("partition must be non-negative", request.correlationId());
            }
            case PRODUCE_BATCH, PRODUCE_IDEMPOTENT, TRANSACT, PRODUCE_CONFIGURED -> {
                requireTopic(request.topic());
                if (request.partition() < 0) throw bad("partition must be non-negative", request.correlationId());
                if (request.valueCount() == 0 || request.valueCount() > MAX_RESPONSE_ITEMS)
                    throw bad("batch must contain between 1 and " + MAX_RESPONSE_ITEMS + " records", request.correlationId());
                long bytes = 0;
                for (int i = 0; i < request.valueCount(); i++) {
                    bytes += request.valueLengthAt(i);
                    if (bytes > MAX_BATCH_BYTES) throw bad("produce batch exceeds maximum bytes", request.correlationId());
                }
                if (request.command() == Command.PRODUCE_IDEMPOTENT || request.command() == Command.TRANSACT) {
                    ProducerIdentity producer = request.options().producer();
                    if (producer == null) throw bad("Producer identity is required", request.correlationId());
                    requireString(producer.id(), "producerId", request.correlationId());
                    if (producer.epoch() < 0 || producer.sequence() < 0)
                        throw bad("Producer epoch and sequence must be non-negative", request.correlationId());
                    if (request.options().acknowledgementMode() != AcknowledgementMode.DURABLE)
                        throw bad("Idempotent writes require durable acknowledgement", request.correlationId());
                }
                if (request.command() == Command.TRANSACT) {
                    ConsumerCommit commit = request.options().commit();
                    if (commit == null) throw bad("Input commit is required", request.correlationId());
                    requireTopic(commit.topic());
                    requireString(commit.groupId(), "groupId", request.correlationId());
                    requireString(commit.memberId(), "memberId", request.correlationId());
                    if (commit.partition() < 0 || commit.nextOffset() < 0 || commit.generation() < -1)
                        throw bad("Invalid input commit boundary", request.correlationId());
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
            case COMMIT_OFFSET, FETCH_OFFSET -> {
                requireTopic(request.topic());
                requireString(request.groupId(), "groupId", request.correlationId());
                if (request.partition() < 0 || request.offset() < 0)
                    throw bad("partition and offset must be non-negative", request.correlationId());
                if (request.memberId() != null)
                    requireString(request.memberId(), "memberId", request.correlationId());
                if (request.command() == Command.FETCH_OFFSET)
                    requireString(request.memberId(), "memberId", request.correlationId());
            }
            case JOIN_GROUP, JOIN_MANAGED_GROUP, GROUP_ASSIGNMENT -> {
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
        long size = HEADER_BYTES + 6L + response.errorMessage().getBytes(StandardCharsets.UTF_8).length;
        if (response.errorCode() == ErrorCode.NONE) {
            if (response.command() == Command.PRODUCE || response.command() == Command.PRODUCE_BATCH
                    || response.command() == Command.FETCH_OFFSET || response.command() == Command.PRODUCE_IDEMPOTENT
                    || response.command() == Command.TRANSACT || response.command() == Command.PRODUCE_CONFIGURED) size += 8;
            if (response.command() == Command.JOIN_MANAGED_GROUP || response.command() == Command.GROUP_ASSIGNMENT) {
                if (response.assignment() == null) throw new IOException("Missing group assignment");
                size += 12L + 12L * response.assignment().nextOffsets().size();
            }
            if (response.command() == Command.METADATA) {
                size += 4;
                for (String topic : response.topics().keySet()) size += 8L + topic.getBytes(StandardCharsets.UTF_8).length;
            }
        }
        ResponseBuffers.reserve(size);
        ProtocolEncodingBuffer bytes = new ProtocolEncodingBuffer(Math.toIntExact(size));
        DataOutputStream out = new DataOutputStream(bytes);
        writeHeader(out, RESPONSE, response.command(), response.correlationId());
        out.writeShort(response.errorCode().id());
        writeString(out, response.errorMessage());
        if (response.errorCode() != ErrorCode.NONE) return bytes.finish();
        switch (response.command()) {
            case PRODUCE, PRODUCE_BATCH, FETCH_OFFSET, PRODUCE_IDEMPOTENT, TRANSACT, PRODUCE_CONFIGURED -> out.writeLong(response.offset());
            case JOIN_MANAGED_GROUP, GROUP_ASSIGNMENT -> {
                out.writeLong(response.assignment().generation());
                out.writeInt(response.assignment().nextOffsets().size());
                for (var entry : response.assignment().nextOffsets().entrySet()) {
                    out.writeInt(entry.getKey());
                    out.writeLong(entry.getValue());
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
        return bytes.finish();
    }

    private static byte[] encodeFetchResponse(BrokerResponse response) throws IOException {
        byte[] message = response.errorMessage().getBytes(StandardCharsets.UTF_8);
        if (message.length > MAX_STRING_BYTES)
            throw new IOException("String field exceeds " + MAX_STRING_BYTES + " bytes");
        long required = HEADER_BYTES + 10L + message.length;
        for (ProtocolRecord record : response.records()) required += 12L + record.valueLength();
        if (required > Integer.MAX_VALUE) throw new IOException("Fetch response is too large to encode");
        ResponseBuffers.reserve(required);
        ByteBuffer output = ByteBuffer.allocate((int) required);
        output.putInt(MAGIC).put((byte) RESPONSE)
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
    public static BrokerResponse decodeResponse(MessageFrame frame) throws ProtocolException {
        ByteBuffer cursor = frame.cursor();
        int correlation = cursor.remaining() >= HEADER_BYTES ? cursor.getInt(CORRELATION_OFFSET) : -1;
        return decodeResponse(cursor, correlation, frame);
    }

    static BrokerResponse decodeResponse(ByteBuffer in, int correlation, MessageFrame frame) throws ProtocolException {
        try {
            // One cursor for the whole frame avoids per-record wrappers and synchronized stream reads.
            ProtocolHeader header = readHeader(in, RESPONSE);
            correlation = header.correlationId();
            ErrorCode errorCode = ErrorCode.fromId(Short.toUnsignedInt(in.getShort()));
            String message = readString(in);
            long offset = -1;
            List<ProtocolRecord> records = List.of();
            Map<String, Integer> topics = Map.of();
            GroupAssignment assignment = null;
            if (errorCode == ErrorCode.NONE) {
                switch (header.command()) {
                    case PRODUCE, PRODUCE_BATCH, FETCH_OFFSET, PRODUCE_IDEMPOTENT, TRANSACT, PRODUCE_CONFIGURED -> offset = in.getLong();
                    case JOIN_MANAGED_GROUP, GROUP_ASSIGNMENT -> {
                        long generation = in.getLong();
                        int count = boundedCount(in.getInt());
                        if (count > 1024 || count > in.remaining() / 12) throw new IOException("Invalid assignment count");
                        Map<Integer, Long> assigned = new LinkedHashMap<>();
                        for (int index = 0; index < count; index++) {
                            int partition = in.getInt();
                            long next = in.getLong();
                            if (partition < 0 || next < 0 || assigned.put(partition, next) != null)
                                throw new IOException("Invalid assignment partition or offset");
                        }
                        assignment = new GroupAssignment(generation, assigned);
                    }
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
            return new BrokerResponse(correlation, header.command(), errorCode, message, offset, records, topics, assignment);
        } catch (ProtocolException e) {
            throw e;
        } catch (EOFException | BufferUnderflowException e) {
            throw bad("Truncated response", correlation);
        } catch (IOException | IllegalArgumentException e) {
            throw bad("Malformed response: " + e.getMessage(), correlation);
        }
    }

    public static byte[] errorResponse(byte[] request, ErrorCode code, String message) {
        ProtocolHeaderParts header = extractHeader(request);
        String safeMessage = message == null ? "" : message;
        while (safeMessage.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_BYTES)
            safeMessage = safeMessage.substring(0, safeMessage.length() - 1);
        return encodeUnchecked(BrokerResponse.error(header.correlationId(), header.command(), code, safeMessage));
    }

    public static byte[] errorResponse(MessageFrame request, ErrorCode code, String message) {
        // Error paths are cold; retain the existing malformed-header handling exactly.
        return errorResponse(request.copy(), code, message);
    }

    private static byte[] encodeUnchecked(BrokerResponse response) {
        try { return encodeResponse(response); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void writeHeader(DataOutputStream out, int kind, Command command, int correlation) throws IOException {
        out.writeInt(MAGIC);
        out.writeByte(kind);
        out.writeShort(command.id());
        out.writeInt(correlation);
    }

    private static ProtocolHeader readHeader(ByteBuffer in, int expectedKind) throws IOException {
        if (in.getInt() != MAGIC) throw bad("Invalid protocol magic", -1);
        if (Byte.toUnsignedInt(in.get()) != expectedKind) throw bad("Unexpected message kind", -1);
        Command command = Command.fromId(Short.toUnsignedInt(in.getShort()));
        int correlation = in.getInt();
        if (correlation < 0) throw bad("correlationId must be non-negative", correlation);
        return new ProtocolHeader(command, correlation);
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

    private static MessageValue readValue(ByteBuffer in, MessageFrame frame) throws IOException {
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

    private static String readString(ByteBuffer in, MessageFrame frame) throws IOException {
        return readString(in, frame, MAX_STRING_BYTES);
    }

    private static String readString(ByteBuffer in, MessageFrame frame, int maxBytes) throws IOException {
        int length = in.getInt();
        if (length < 0 || length > maxBytes || length > in.remaining())
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
        ProtocolHeaderParts header = extractHeader(payload);
        return header.correlationId();
    }

    private static ProtocolHeaderParts extractHeader(byte[] payload) {
        if (payload == null || payload.length < HEADER_BYTES) return new ProtocolHeaderParts(Command.METADATA, 0);
        ProtocolByteReader reader = new ProtocolByteReader(payload);
        int magic = reader.intAt(0);
        int commandId = reader.unsignedShortAt(COMMAND_OFFSET);
        int correlation = reader.intAt(CORRELATION_OFFSET);
        Command command = Command.METADATA;
        if (magic == MAGIC) {
            try { command = Command.fromId(commandId); }
            catch (ProtocolException ignored) { }
        }
        if (correlation < 0) correlation = 0;
        return new ProtocolHeaderParts(command, correlation);
    }

    private static ProtocolException bad(String message, int correlation) {
        return new ProtocolException(ErrorCode.BAD_REQUEST, message, Math.max(correlation, 0));
    }
}
