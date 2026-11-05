package io.setu.security.quota;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TokenBucketRateLimiter implements RateLimiter {

    private final Map<String, ClientBuckets> buckets = new ConcurrentHashMap<>();

    public void registerPolicy(String principal, QuotaPolicy policy) {

        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("Principal must not be null or blank");
        }

        if (policy == null) {
            throw new IllegalArgumentException("Quota policy must not be null");
        }

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

        ClientBuckets clientBuckets = buckets.get(principal);
        return clientBuckets != null && clientBuckets.tryAcquire(messageCount, byteCount);
    }
}
