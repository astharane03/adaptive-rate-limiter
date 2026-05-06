package com.ratelimiter.adaptive_rate_limiter.ratelimit;

import com.ratelimiter.adaptive_rate_limiter.model.RateLimitRule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Returns the correct RateLimiter based on the rule's algorithm field.
 * The filter chain calls this — never instantiates limiters directly.
 *
 * Adding a new algorithm in future = add enum value + one case here.
 * Nothing else changes.
 */
@Component
@RequiredArgsConstructor
public class RateLimiterFactory {

    private final TokenBucketRateLimiter tokenBucketRateLimiter;
    private final SlidingWindowRateLimiter slidingWindowRateLimiter;

    public RateLimiter getLimiter(RateLimitRule rule) {
        return switch (rule.getAlgorithm()) {
            case TOKEN_BUCKET   -> tokenBucketRateLimiter;
            case SLIDING_WINDOW -> slidingWindowRateLimiter;
        };
    }
}