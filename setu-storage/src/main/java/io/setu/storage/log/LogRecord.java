package io.setu.storage.log;

public record LogRecord(long offset, byte[] value) {
    public LogRecord {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        value = value.clone();
    }

    @Override
    public byte[] value() {
        return value.clone();
    }
}
