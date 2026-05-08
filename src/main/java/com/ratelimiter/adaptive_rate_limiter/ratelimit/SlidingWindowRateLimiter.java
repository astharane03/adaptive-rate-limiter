package com.ratelimiter.adaptive_rate_limiter.ratelimit;

import com.ratelimiter.adaptive_rate_limiter.model.RateLimitRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Executes sliding_window.lua atomically in Redis.
 *
 * HOW IT WORKS:
 * Stores every request timestamp in a Redis Sorted Set.
 * Score = timestamp in milliseconds.
 * To check: count all entries with score > (now - windowMs).
 * If count >= limit → deny. Else → add timestamp + allow.
 *
 * DIFFERENCE FROM TOKEN BUCKET:
 * Token bucket allows bursts if the bucket was full.
 * Sliding window counts EXACTLY how many requests happened
 * in the last N seconds — no burst allowance at all.
 *
 * Use sliding window when you need strict, smooth enforcement.
 * Use token bucket when short bursts are acceptable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlidingWindowRateLimiter implements RateLimiter {

    private final RedisTemplate<String, String> redisTemplate;
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> slidingWindowScript;

    private static final String KEY_PREFIX = "rl:sw:";

    @Override
    public RateLimitResult tryAcquire(String clientKey, RateLimitRule rule) {
        String redisKey = KEY_PREFIX + clientKey;

        long windowMs = (long) rule.getWindowSizeSeconds() * 1000;
        long limit    = rule.getRequestsPerWindow();
        long nowMs    = System.currentTimeMillis();

        try {
            // Execute the Lua script atomically in Redis
            // Keys: [redisKey]
            // Args: [limit, windowMs, nowMs]
            List<Long> result = redisTemplate.execute(
                    slidingWindowScript,
                    List.of(redisKey),
                    String.valueOf(limit),
                    String.valueOf(windowMs),
                    String.valueOf(nowMs)
            );

            if (result == null || result.size() < 3) {
                log.error("Sliding window Lua script returned null/incomplete for key={}", redisKey);
                return RateLimitResult.allow(limit, limit);
            }

            long allowed      = ((Number) result.get(0)).longValue();
            long currentCount = ((Number) result.get(1)).longValue();
            long retryAfter   = ((Number) result.get(2)).longValue();

            if (allowed == 1L) {
                long remaining = limit - currentCount;
                log.debug("Sliding window ALLOWED key={} count={}/{}", redisKey, currentCount, limit);
                return RateLimitResult.allow(remaining, limit);
            } else {
                log.debug("Sliding window DENIED key={} count={}/{} retryAfter={}s",
                        redisKey, currentCount, limit, retryAfter);
                return RateLimitResult.deny(retryAfter, limit);
            }

        } catch (Exception e) {
            log.error("Redis error during sliding window check for key={}: {}", redisKey, e.getMessage());
            return RateLimitResult.allow(limit, limit);
        }
    }
}









