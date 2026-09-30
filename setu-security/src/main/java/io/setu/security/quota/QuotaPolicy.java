package io.setu.security.quota;

public record QuotaPolicy(
        long messagesPerSecond,
        long messageBurstCapacity,
        long bytesPerSecond,
        long byteBurstCapacity
) {

    public QuotaPolicy {
        if (messagesPerSecond <= 0) {
            throw new IllegalArgumentException("Messages per second must be positive");
        }

        if (messageBurstCapacity <= 0) {
            throw new IllegalArgumentException("Message burst capacity must be positive");
        }

        if (bytesPerSecond <= 0) {
            throw new IllegalArgumentException("Bytes per second must be positive");
        }

        if (byteBurstCapacity <= 0) {
            throw new IllegalArgumentException("Byte burst capacity must be positive");
        }

        if (messageBurstCapacity < messagesPerSecond) {
            throw new IllegalArgumentException("Message burst capacity must be >= messages per second");
        }

        if (byteBurstCapacity < bytesPerSecond) {
            throw new IllegalArgumentException("Byte burst capacity must be >= bytes per second");
        }
    }
}