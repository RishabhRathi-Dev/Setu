package io.setu.core.protocol;

import io.setu.storage.log.MessageValue;

public record ProtocolRecord(long offset, MessageValue payload) {
    public ProtocolRecord(long offset, byte[] value) {
        this(offset, MessageValue.copyOf(value));
    }

    public ProtocolRecord {
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
        java.util.Objects.requireNonNull(payload, "payload");
    }

    public byte[] value() { return payload.copy(); }

    public int valueLength() { return payload.length(); }
}
