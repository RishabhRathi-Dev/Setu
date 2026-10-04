package io.setu.security.quota;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotaTests {

    @Test
    void policyRequiresPositiveRatesAndSufficientBurstCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new QuotaPolicy(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new QuotaPolicy(2, 1, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> new QuotaPolicy(1, 1, 2, 1));
        assertDoesNotThrow(() -> new QuotaPolicy(1, 1, 1, 1));
    }

    @Test
    void tokenBucketValidatesAndConsumesCapacity() {
        TokenBucket bucket = new TokenBucket(2, 1);

        assertTrue(bucket.hasCapacity(2));
        assertTrue(bucket.tryConsume(2));
        assertFalse(bucket.hasCapacity(1));
        assertThrows(IllegalStateException.class, () -> bucket.consume(1));
        assertThrows(IllegalArgumentException.class, () -> bucket.tryConsume(-1));
        assertTrue(bucket.tryConsume(0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(1, 0));
    }

    @Test
    void rateLimiterKeepsPrincipalsIndependentAndAcquiresBothDimensionsAtomically() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter();
        QuotaPolicy policy = new QuotaPolicy(2, 2, 10, 20);
        limiter.registerPolicy("client-a", policy);
        limiter.registerPolicy("client-b", policy);

        assertTrue(limiter.tryAcquire("client-a", 2, 10));
        assertFalse(limiter.tryAcquire("client-a", 1, 0));
        assertTrue(limiter.tryAcquire("client-a", 0, 10),
                "a failed message check must not consume byte tokens");
        assertTrue(limiter.tryAcquire("client-b", 2, 10));
        assertFalse(limiter.tryAcquire("unknown", 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> limiter.tryAcquire("client-a", 1, -1));
    }

    @Test
    void policyRegistrationRejectsInvalidInputs() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter();
        QuotaPolicy policy = new QuotaPolicy(1, 1, 1, 1);

        assertThrows(IllegalArgumentException.class, () -> limiter.registerPolicy(" ", policy));
        assertThrows(IllegalArgumentException.class, () -> limiter.registerPolicy("client", null));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(" ", 1, 1));
    }

    @Test
    void quotaManagerReturnsAllowAndDenyDecisions() {
        RateLimiter limiter = (principal, messages, bytes) -> principal.equals("allowed");
        QuotaManager manager = new QuotaManager(limiter);

        assertTrue(manager.check("allowed", 1, 1).allowed());
        assertEquals("Quota exceeded", manager.check("limited", 1, 1).reason());
        assertEquals("Principal must not be null or blank", manager.check(" ", 1, 1).reason());
        assertThrows(IllegalArgumentException.class, () -> new QuotaManager(null));
    }
}
