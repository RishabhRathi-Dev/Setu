package io.setu.core.protocol;

import java.util.ArrayList;
import java.util.List;

public record BrokerRequest(int correlationId, Command command, String topic, int partition,
                            long offset, int maxBytes, byte[] value, int partitionCount,
                            String groupId, String memberId, List<byte[]> values) {
    public BrokerRequest(int correlationId, Command command, String topic, int partition,
                         long offset, int maxBytes, byte[] value, int partitionCount,
                         String groupId, String memberId) {
        this(correlationId, command, topic, partition, offset, maxBytes, value, partitionCount,
                groupId, memberId, List.of());
    }

    public BrokerRequest {
        if (correlationId < 0) throw new IllegalArgumentException("correlationId must be non-negative");
        if (command == null) throw new IllegalArgumentException("command must not be null");
        value = value == null ? new byte[0] : value.clone();
        List<byte[]> copied = new ArrayList<>();
        if (values != null) for (byte[] item : values) copied.add(item.clone());
        values = List.copyOf(copied);
    }

    @Override
    public byte[] value() { return value.clone(); }

    @Override
    public List<byte[]> values() { return values.stream().map(byte[]::clone).toList(); }

    public int valueCount() { return values.size(); }

    public byte[] valueAt(int index) { return values.get(index).clone(); }
}
