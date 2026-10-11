package io.setu.core.protocol;

public record ProtocolRecord(long offset, byte[] value) {
    public ProtocolRecord {
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
        value = value.clone();
    }

    @Override
    public byte[] value() { return value.clone(); }

    public int valueLength() { return value.length; }

    // Package-private encoding access never exposes the owned array to callers.
    byte[] valueForEncoding() { return value; }
}
