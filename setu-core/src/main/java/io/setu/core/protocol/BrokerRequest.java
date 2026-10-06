package io.setu.core.protocol;

public record BrokerRequest(int correlationId, Command command, String topic, int partition,
                            long offset, int maxBytes, byte[] value, int partitionCount,
                            String groupId, String memberId) {
    public BrokerRequest {
        if (correlationId < 0) throw new IllegalArgumentException("correlationId must be non-negative");
        if (command == null) throw new IllegalArgumentException("command must not be null");
        value = value == null ? new byte[0] : value.clone();
    }

    @Override
    public byte[] value() { return value.clone(); }
}
