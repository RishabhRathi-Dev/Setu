package io.setu.security.quota;

final class ClientBuckets {

    private final long messageCapacity;
    private final long byteCapacity;
    private final double messagesPerNano;
    private final double bytesPerNano;
    private double messageTokens;
    private double byteTokens;
    private long lastRefillTime;

    private ClientBuckets(QuotaPolicy policy) {

        messageCapacity = policy.messageBurstCapacity();
        byteCapacity = policy.byteBurstCapacity();
        messagesPerNano = policy.messagesPerSecond() / 1_000_000_000.0;
        bytesPerNano = policy.bytesPerSecond() / 1_000_000_000.0;
        messageTokens = messageCapacity;
        byteTokens = byteCapacity;
        lastRefillTime = System.nanoTime();
    }

    static ClientBuckets from(QuotaPolicy policy) {
        return new ClientBuckets(policy);
    }

    /**
     * Checks and consumes both quota dimensions atomically.
     *
     * A request must have sufficient capacity in both the
     * message bucket and byte bucket. If either bucket cannot
     * satisfy the request, neither bucket is modified.
     */
    synchronized boolean tryAcquire(long messageCount, long byteCount) {

        long now = System.nanoTime();
        long elapsed = now - lastRefillTime;
        if (elapsed > 0) {
            messageTokens = Math.min(messageCapacity, messageTokens + elapsed * messagesPerNano);
            byteTokens = Math.min(byteCapacity, byteTokens + elapsed * bytesPerNano);
            lastRefillTime = now;
        }
        if (messageTokens < messageCount || byteTokens < byteCount) return false;
        messageTokens -= messageCount;
        byteTokens -= byteCount;
        return true;
    }
}
