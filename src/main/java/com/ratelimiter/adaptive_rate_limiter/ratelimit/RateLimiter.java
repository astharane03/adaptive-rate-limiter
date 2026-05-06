package com.ratelimiter.adaptive_rate_limiter.ratelimit;

import com.ratelimiter.adaptive_rate_limiter.model.RateLimitRule;


//common interface for al rate limiting algorithms
public interface RateLimiter {
    RateLimitResult tryAcquire(String clientKey, RateLimitRule rule);
}
