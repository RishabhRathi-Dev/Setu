package io.setu.security.quota;

public final class QuotaManager {

    private final RateLimiter rateLimiter;

    public QuotaManager(RateLimiter rateLimiter) {

        if (rateLimiter == null) {
            throw new IllegalArgumentException("Rate limiter must not be null");
        }

        this.rateLimiter = rateLimiter;
    }

    public QuotaDecision check(String principal, long messageCount, long byteCount) {

        if (principal == null || principal.isBlank()) {
            return QuotaDecision.deny(
                    "Principal must not be null or blank"
            );
        }

        boolean allowed = rateLimiter.tryAcquire(
                        principal,
                        messageCount,
                        byteCount
                );

        if (!allowed) {
            return QuotaDecision.deny(
                    "Quota exceeded"
            );
        }

        return QuotaDecision.allow();
    }
}
