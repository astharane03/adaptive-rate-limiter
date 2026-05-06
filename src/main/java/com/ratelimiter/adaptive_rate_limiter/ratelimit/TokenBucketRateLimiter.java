package com.ratelimiter.adaptive_rate_limiter.ratelimit;

import com.ratelimiter.adaptive_rate_limiter.model.RateLimitRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Executes token_bucket.lua atomically in Redis.
 *
 * HOW IT WORKS:
 * Each client has a "bucket" in Redis holding N tokens.
 * Tokens refill at a steady rate (requestsPerWindow / windowSizeSeconds).
 * Each request consumes 1 token.
 * Empty bucket = request denied.
 *
 * KEY INTERVIEW POINT — why Lua?
 * The check-and-decrement must be atomic. If two requests arrive
 * simultaneously and both check "do I have tokens?" before either
 * decrements, both see "yes" and both get through — breaking the limit.
 * Lua runs as a single atomic operation inside Redis.
 * No race condition possible.
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class TokenBucketRateLimiter implements RateLimiter{

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<List> tokenBucketScript;

    private static final String KEY_PREFIX = "rl:tb:";

    @Override
    public RateLimitResult tryAcquire(String clientKey, RateLimitRule rule) {
        String redisKey = KEY_PREFIX + clientKey;

        double refillRate = (double) rule.getRequestsPerWindow() / rule.getWindowSizeSeconds();

        int capacity = rule.getRequestsPerWindow() + rule.getBurstCapacity();

        long nowMs = System.currentTimeMillis();

        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redisTemplate.execute(
                    tokenBucketScript,
                    List.of(redisKey),
                    String.valueOf(capacity),
                    String.valueOf(refillRate),
                    String.valueOf(nowMs),
                    "1"
            );
            if(result == null || result.size() < 3) {
                log.error("Token bucket Lua Script returned null/incomplete for key = {}", redisKey);
                return RateLimitResult.allow(capacity,capacity);
            }
            long allowed    = ((Number) result.get(0)).longValue();
            long remaining  = ((Number) result.get(1)).longValue();
            long retryAfter = ((Number) result.get(2)).longValue();

            if(allowed == 1L) {
                log.debug("Token bucket ALLOWED key = {} remaining = {}", redisKey, remaining);
                return RateLimitResult.allow(remaining, capacity);
            }
            else {
                log.debug("Token bucket DENIED key = {} retryAfter {}s", redisKey, retryAfter);
                return RateLimitResult.deny(retryAfter, capacity);
            }
        }
        catch (Exception e) {
            log.error("Redis error during token bucket check for key = {} : {}", redisKey, e.getMessage());
            return RateLimitResult.allow(capacity,capacity);
        }
    }

}
