package com.ratelimiter.adaptive_rate_limiter.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Central metrics registry for the rate limiter.
 *
 * All metrics are exposed at /actuator/prometheus
 * and scraped by Prometheus every 5 seconds.
 *
 * Metrics created here:
 *
 * rate_limit_requests_total{result, client, path}
 *   → counts every allow/block decision
 *   → Grafana: requests/sec, block rate per client
 *
 * rate_limit_check_duration_seconds{client}
 *   → time taken for Redis Lua script to execute
 *   → Grafana: p99 latency of rate limit check
 *
 * risk_score{client}
 *   → current risk score per client (0.0 to 1.0)
 *   → Grafana: which clients are being throttled
 *
 * gateway_errors_total{client}
 *   → counts downstream errors per client
 *   → Grafana: which clients generate most errors
 */
@Component
@RequiredArgsConstructor
public class RateLimiterMetrics {

    private final MeterRegistry meterRegistry;

    // Cache risk scores so Gauge can read them
    // Gauge needs a supplier — we store latest score here
    private final ConcurrentHashMap<String, Double> riskScoreCache
            = new ConcurrentHashMap<>();

    // ── Request counters ──────────────────────────────────────

    public void recordAllowed(String clientKey, String path) {
        Counter.builder("rate_limit_requests_total")
                .tag("result", "allowed")
                .tag("client", sanitize(clientKey))
                .tag("path", sanitizePath(path))
                .description("Total rate limit decisions")
                .register(meterRegistry)
                .increment();
    }

    public void recordBlocked(String clientKey, String path) {
        Counter.builder("rate_limit_requests_total")
                .tag("result", "blocked")
                .tag("client", sanitize(clientKey))
                .tag("path", sanitizePath(path))
                .description("Total rate limit decisions")
                .register(meterRegistry)
                .increment();
    }

    // ── Latency timer ─────────────────────────────────────────

    /**
     * Records how long the Redis Lua script took to execute.
     * Called in RateLimitFilter wrapping the tryAcquire() call.
     *
     * Usage:
     *   long start = System.nanoTime();
     *   RateLimitResult result = rateLimiter.tryAcquire(...);
     *   metrics.recordCheckDuration(clientKey, System.nanoTime() - start);
     */
    public void recordCheckDuration(String clientKey, long durationNanos) {
        Timer.builder("rate_limit_check_duration_seconds")
                .tag("client", sanitize(clientKey))
                .description("Time taken for rate limit check in Redis")
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    // ── Risk score gauge ──────────────────────────────────────

    /**
     * Updates the risk score for a client.
     * Gauge reads from riskScoreCache — always shows latest value.
     *
     * Called in RateLimitFilter after riskScorer.score() returns.
     */
    public void updateRiskScore(String clientKey, double score) {
        String sanitizedKey = sanitize(clientKey);
        riskScoreCache.put(sanitizedKey, score);

        // Register gauge only once per client key
        // Micrometer is idempotent — safe to call multiple times
        Gauge.builder("risk_score", riskScoreCache,
                        cache -> cache.getOrDefault(sanitizedKey, 0.0))
                .tag("client", sanitizedKey)
                .description("Current risk score per client (0.0 to 1.0)")
                .register(meterRegistry);
    }

    // ── Error counter ─────────────────────────────────────────

    /**
     * Records a downstream error for a client.
     * Called in GatewayController catch blocks.
     */
    public void recordDownstreamError(String clientKey) {
        Counter.builder("gateway_errors_total")
                .tag("client", sanitize(clientKey))
                .description("Total downstream errors per client")
                .register(meterRegistry)
                .increment();
    }

    // ── Shadow mode counter ───────────────────────────────────

    /**
     * Records when shadow mode overrides a block decision.
     * Called in ShadowDecisionLogger.
     */
    public void recordShadowOverride(String clientKey) {
        Counter.builder("shadow_mode_overrides_total")
                .tag("client", sanitize(clientKey))
                .description("Blocks overridden by shadow mode")
                .register(meterRegistry)
                .increment();
    }

    // ── Sanitizers ────────────────────────────────────────────

    /**
     * Prevents high-cardinality IP labels from exploding Prometheus.
     * IP-based clients all collapse to "ip:anonymous".
     * API key clients keep their key (already low cardinality).
     */
    private String sanitize(String clientKey) {
        if (clientKey == null) return "unknown";
        return clientKey.startsWith("ip:") ? "ip:anonymous" : clientKey;
    }

    /**
     * Collapses dynamic path segments to prevent cardinality explosion.
     * /api/v1/users/12345 → /api/v1/users/{id}
     * /api/v1/orders/abc  → /api/v1/orders/{id}
     */
    private String sanitizePath(String path) {
        if (path == null) return "unknown";
        // Replace numeric or UUID-like segments with {id}
        return path.replaceAll("/[0-9a-f]{8}-[0-9a-f-]{27}", "/{id}")
                .replaceAll("/\\d+", "/{id}");
    }
}