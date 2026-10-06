package io.setu.core.protocol;

public record ProtocolRecord(long offset, byte[] value) {
    public ProtocolRecord {
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
        value = value.clone();
    }

    @Override
    public byte[] value() { return value.clone(); }
}
