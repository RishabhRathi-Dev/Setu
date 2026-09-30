package io.setu.security.quota;

public final class TokenBucket {

    private final long capacity;
    private final double refillRatePerNano;

    private double tokens;
    private long lastRefillTime;

    public TokenBucket(
            long capacity,
            long refillRatePerSecond) {

        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }

        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("Refill rate must be positive");
        }

        this.capacity = capacity;

        this.refillRatePerNano = (double) refillRatePerSecond / 1_000_000_000.0;

        this.tokens = capacity;
        this.lastRefillTime = System.nanoTime();
    }

    /**
     * Checks whether the requested amount can be consumed
     * without modifying the bucket state.
     *
     * Refill is applied before checking availability so that
     * the decision reflects the current time.
     */
    public synchronized boolean hasCapacity(long amount) {

        validateAmount(amount);

        refill();

        return tokens >= amount;
    }

    /**
     * Consumes tokens from the bucket.
     *
     * This method assumes the caller has already verified
     * that sufficient capacity exists.
     */
    public synchronized void consume(long amount) {

        validateAmount(amount);

        if (tokens < amount) {
            throw new IllegalStateException("Insufficient token capacity");
        }

        tokens -= amount;
    }

    /**
     * Attempts to consume tokens atomically for this bucket.
     */
    public synchronized boolean tryConsume(long amount) {

        validateAmount(amount);

        refill();

        if (tokens < amount) {
            return false;
        }

        tokens -= amount;

        return true;
    }

    private void validateAmount(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Amount must not be negative");
        }
    }

    private void refill() {
        long now = System.nanoTime();

        long elapsed = now - lastRefillTime;

        if (elapsed <= 0) {
            return;
        }

        double refillAmount = elapsed * refillRatePerNano;

        tokens =
                Math.min(
                        capacity,
                        tokens + refillAmount
                );

        lastRefillTime = now;
    }
}