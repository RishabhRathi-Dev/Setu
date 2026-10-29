package io.setu.security.quota;

final class ClientBuckets {

    private final TokenBucket messageBucket;
    private final TokenBucket byteBucket;

    private ClientBuckets(QuotaPolicy policy) {

        this.messageBucket =
                new TokenBucket(
                        policy.messageBurstCapacity(),
                        policy.messagesPerSecond()
                );

        this.byteBucket =
                new TokenBucket(
                        policy.byteBurstCapacity(),
                        policy.bytesPerSecond()
                );
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

        boolean messageCapacity = messageBucket.hasCapacity(messageCount);

        boolean byteCapacity = byteBucket.hasCapacity(byteCount);

        if (!messageCapacity || !byteCapacity) {
            return false;
        }

        messageBucket.consume(messageCount);
        byteBucket.consume(byteCount);

        return true;
    }
}
