package io.setu.storage.log;

public record LogRecord(long offset, MessageValue payload) {
    public LogRecord(long offset, byte[] value) {
        this(offset, MessageValue.copyOf(value));
    }

    public LogRecord {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        java.util.Objects.requireNonNull(payload, "payload");
    }

    public byte[] value() {
        return payload.copy();
    }

    public int valueLength() { return payload.length(); }
}
