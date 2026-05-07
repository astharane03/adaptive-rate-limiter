package com.ratelimiter.adaptive_rate_limiter.risk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Tracks per-client behavior signals in Redis.
 *
 * Uses two Redis Sorted Sets per client:
 *
 * 1. risk:burst:{clientKey}
 *    Members = request timestamps in the last BURST_WINDOW_MS
 *    Used to detect: are requests arriving in tight bursts?
 *
 * 2. risk:errors:{clientKey}
 *    Members = error timestamps in the last ERROR_WINDOW_MS
 *    Used to detect: is this client generating lots of errors?
 *
 * WHY sorted sets?
 * Same reason as sliding window — ZREMRANGEBYSCORE lets us
 * efficiently remove old entries and count recent ones.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClientBehaviorTracker {

    private final RedisTemplate<String, String> redisTemplate;

    // Track requests in a 10-second window to detect bursts
    private static final long BURST_WINDOW_MS  = 10_000;

    // Track errors in a 60-second window
    private static final long ERROR_WINDOW_MS  = 60_000;

    // Max requests in BURST_WINDOW before considered "bursty"
    private static final int  BURST_THRESHOLD  = 20;

    // Max errors in ERROR_WINDOW before considered "error-prone"
    private static final int  ERROR_THRESHOLD  = 10;

    private static final String BURST_PREFIX = "risk:burst:";
    private static final String ERROR_PREFIX = "risk:errors:";

    // ── Record signals ────────────────────────────────────────

    /**
     * Called on every request — records the timestamp.
     * RateLimitFilter calls this regardless of allow/deny.
     */
    public void recordRequest(String clientKey) {
        long nowMs = System.currentTimeMillis();
        String key = BURST_PREFIX + clientKey;

        // Add timestamp as both score and member (with random suffix for uniqueness)
        String member = nowMs + "-" + Math.round(Math.random() * 1_000_000);
        redisTemplate.opsForZSet().add(key, member, nowMs);

        // Remove entries outside the burst window
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, nowMs - BURST_WINDOW_MS);

        // Auto-expire the key after 2x the window
        redisTemplate.expire(key, java.time.Duration.ofMillis(BURST_WINDOW_MS * 2));
    }

    /**
     * Called when a downstream response is 4xx or 5xx.
     * GatewayController calls this after getting the downstream response.
     */
    public void recordError(String clientKey) {
        long nowMs = System.currentTimeMillis();
        String key = ERROR_PREFIX + clientKey;

        String member = nowMs + "-" + Math.round(Math.random() * 1_000_000);
        redisTemplate.opsForZSet().add(key, member, nowMs);

        redisTemplate.opsForZSet().removeRangeByScore(key, 0, nowMs - ERROR_WINDOW_MS);

        redisTemplate.expire(key, java.time.Duration.ofMillis(ERROR_WINDOW_MS * 2));
    }

    // ── Read signals ──────────────────────────────────────────

    /**
     * Returns a value between 0.0 and 1.0 representing how "bursty"
     * this client is in the last BURST_WINDOW_MS.
     *
     * 0.0 = no requests or well spread out
     * 1.0 = firing BURST_THRESHOLD or more requests in the window
     */
    public double getBurstRatio(String clientKey) {
        String key = BURST_PREFIX + clientKey;
        long nowMs = System.currentTimeMillis();

        // Remove stale entries first
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, nowMs - BURST_WINDOW_MS);

        Long count = redisTemplate.opsForZSet().zCard(key);
        if (count == null || count == 0) return 0.0;

        // Normalize: how close are we to the burst threshold?
        return Math.min(1.0, (double) count / BURST_THRESHOLD);
    }

    /**
     * Returns a value between 0.0 and 1.0 representing the error rate.
     *
     * 0.0 = no errors
     * 1.0 = ERROR_THRESHOLD or more errors in the window
     */
    public double getErrorRate(String clientKey) {
        String key = ERROR_PREFIX + clientKey;
        long nowMs = System.currentTimeMillis();

        redisTemplate.opsForZSet()
                .removeRangeByScore(key, 0, nowMs - ERROR_WINDOW_MS);

        Long errorCount = redisTemplate.opsForZSet().zCard(key);
        if (errorCount == null || errorCount == 0) return 0.0;

        return Math.min(1.0, (double) errorCount / ERROR_THRESHOLD);
    }

    /**
     * Scheduled cleanup — removes all stale risk keys every 5 minutes.
     * Prevents Redis from accumulating keys for clients that
     * stopped making requests.
     *
     * @EnableScheduling on the main class makes this work.
     */
    @Scheduled(fixedDelay = 300_000)
    public void cleanupStaleKeys() {
        try {
            Set<String> burstKeys = redisTemplate.keys(BURST_PREFIX + "*");
            Set<String> errorKeys = redisTemplate.keys(ERROR_PREFIX + "*");

            int cleaned = 0;
            long nowMs = System.currentTimeMillis();

            if (burstKeys != null) {
                for (String key : burstKeys) {
                    redisTemplate.opsForZSet().removeRangeByScore(key, 0, nowMs - BURST_WINDOW_MS);
                    Long remaining = redisTemplate.opsForZSet().zCard(key);
                    if (remaining != null && remaining == 0) {
                        redisTemplate.delete(key);
                        cleaned++;
                    }
                }
            }

            if (errorKeys != null) {
                for (String key : errorKeys) {
                    redisTemplate.opsForZSet().removeRangeByScore(key, 0, nowMs - ERROR_WINDOW_MS);
                    Long remaining = redisTemplate.opsForZSet().zCard(key);
                    if (remaining != null && remaining == 0) {
                        redisTemplate.delete(key);
                        cleaned++;
                    }
                }
            }

            if (cleaned > 0) {
                log.info("Risk tracker cleanup: removed {} stale keys", cleaned);
            }

        } catch (Exception e) {
            log.error("Error during risk key cleanup: {}", e.getMessage());
        }
    }
}