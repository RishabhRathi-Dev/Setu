package io.setu.security.quota;

public interface RateLimiter {

    boolean tryAcquire(
            String principal,
            long messageCount,
            long byteCount
    );
}