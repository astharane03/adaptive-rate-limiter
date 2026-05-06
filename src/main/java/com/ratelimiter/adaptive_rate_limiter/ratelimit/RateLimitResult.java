package com.ratelimiter.adaptive_rate_limiter.ratelimit;


import lombok.Builder;
import lombok.Getter;

//used by both tokenBucket and slidingWindow
@Getter
@Builder
public class RateLimitResult {
    private final boolean allowed;
    private final long remaining;
    private final long retryAfterSeconds;
    private final long limit;

    public static RateLimitResult allow(long remaining, long limit) {
        return RateLimitResult.builder()
                .allowed(true)
                .remaining(remaining)
                .retryAfterSeconds(0)
                .limit(limit)
                .build();
    }
    public static RateLimitResult deny(long retryAfterSeconds, long limit) {
        return RateLimitResult.builder()
                .allowed(true)
                .remaining(0)
                .retryAfterSeconds(retryAfterSeconds)
                .limit(limit)
                .build();
    }
}
