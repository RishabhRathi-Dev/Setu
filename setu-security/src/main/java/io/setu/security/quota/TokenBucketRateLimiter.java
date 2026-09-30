package io.setu.security.quota;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TokenBucketRateLimiter implements RateLimiter {

    private final Map<String, ClientBuckets> buckets = new ConcurrentHashMap<>();

    private final Map<String, QuotaPolicy> policies = new ConcurrentHashMap<>();

    public void registerPolicy(String principal, QuotaPolicy policy) {

        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("Principal must not be null or blank");
        }

        if (policy == null) {
            throw new IllegalArgumentException("Quota policy must not be null");
        }

        policies.put(principal, policy);

        buckets.put(
                principal,
                ClientBuckets.from(policy)
        );
    }

    @Override
    public boolean tryAcquire(String principal, long messageCount, long byteCount) {

        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("Principal must not be null or blank");
        }

        if (messageCount < 0 || byteCount < 0) {
            throw new IllegalArgumentException("Message and byte counts must not be negative");
        }

        QuotaPolicy policy = policies.get(principal);

        if (policy == null) {
            return false;
        }

        ClientBuckets clientBuckets =
                buckets.computeIfAbsent(
                        principal,
                        ignored -> ClientBuckets.from(policy)
                );

        return clientBuckets.tryAcquire(
                messageCount,
                byteCount
        );
    }

    private static final class ClientBuckets {

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

        private static ClientBuckets from(QuotaPolicy policy) {
            return new ClientBuckets(policy);
        }

        /**
         * Checks and consumes both quota dimensions atomically.
         *
         * A request must have sufficient capacity in both the
         * message bucket and byte bucket. If either bucket cannot
         * satisfy the request, neither bucket is modified.
         */
        private synchronized boolean tryAcquire(long messageCount, long byteCount) {

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
}