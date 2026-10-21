package io.setu.core.protocol;

import io.setu.storage.log.MessageValue;
import java.util.List;

public record BrokerRequest(int correlationId, Command command, String topic, int partition,
                            long offset, int maxBytes, MessageValue payload, int partitionCount,
                            String groupId, String memberId, List<MessageValue> payloads, int waitMillis,
                            RequestOptions options) {
    public BrokerRequest(int correlationId, Command command, String topic, int partition, long offset, int maxBytes,
                         MessageValue payload, int partitionCount, String groupId, String memberId,
                         List<MessageValue> payloads, int waitMillis) {
        this(correlationId, command, topic, partition, offset, maxBytes, payload, partitionCount,
                groupId, memberId, payloads, waitMillis, RequestOptions.DEFAULT);
    }
    public BrokerRequest(int correlationId, Command command, String topic, int partition,
                         long offset, int maxBytes, byte[] value, int partitionCount,
                         String groupId, String memberId) {
        this(correlationId, command, topic, partition, offset, maxBytes, value, partitionCount,
                groupId, memberId, List.of());
    }

    public BrokerRequest(int id, Command command, String topic, int partition, long offset, int maxBytes,
                         byte[] value, int partitionCount, String group, String member, List<byte[]> values) {
        this(id, command, topic, partition, offset, maxBytes,
                value == null ? MessageValue.empty() : MessageValue.copyOf(value), partitionCount, group, member,
                values == null ? List.of() : values.stream().map(MessageValue::copyOf).toList(), 0);
    }

    public BrokerRequest {
        if (correlationId < 0) throw new IllegalArgumentException("correlationId must be non-negative");
        if (command == null) throw new IllegalArgumentException("command must not be null");
        payload = payload == null ? MessageValue.empty() : payload;
        payloads = payloads == null ? List.of() : List.copyOf(payloads);
        options = options == null ? RequestOptions.DEFAULT : options;
        if (waitMillis < 0 || waitMillis > 30_000) throw new IllegalArgumentException("waitMillis must be 0..30000");
    }

    public byte[] value() { return payload.copy(); }

    public List<byte[]> values() { return payloads.stream().map(MessageValue::copy).toList(); }

    public int valueCount() { return payloads.size(); }

    public byte[] valueAt(int index) { return payloads.get(index).copy(); }

    public int valueLength() { return payload.length(); }

    public int valueLengthAt(int index) { return payloads.get(index).length(); }

    public BrokerRequest withWaitMillis(int millis) {
        return new BrokerRequest(correlationId, command, topic, partition, offset, maxBytes, payload,
                partitionCount, groupId, memberId, payloads, millis, options);
    }

    public BrokerRequest withOptions(RequestOptions updated) {
        return new BrokerRequest(correlationId, command, topic, partition, offset, maxBytes, payload,
                partitionCount, groupId, memberId, payloads, waitMillis, updated);
    }

    public BrokerRequest withGeneration(long generation) {
        return withOptions(new RequestOptions(options.producer(), options.commit(), options.acknowledgementMode(), generation));
    }
}
