package com.ratelimiter.adaptive_rate_limiter.shadow;

import com.ratelimiter.adaptive_rate_limiter.metrics.RateLimiterMetrics;
import com.ratelimiter.adaptive_rate_limiter.model.GatewayRequest;
import com.ratelimiter.adaptive_rate_limiter.model.GatewayResponse;
import com.ratelimiter.adaptive_rate_limiter.model.ClientIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logs what WOULD have happened if shadow mode was not active.
 *
 * Output is structured so you can:
 *   - grep "[SHADOW]" to find all shadow decisions
 *   - Count how many real users would have been blocked
 *   - Confirm the rule is working before enforcing it
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShadowDecisionLogger {

    private final RateLimiterMetrics metrics;

    /**
     * Called when a rule would have blocked a request
     * but shadow mode prevented actual enforcement.
     */
    public void logWouldHaveBlocked(GatewayRequest request,
                                    GatewayResponse wouldHaveBlocked) {

        String clientKey = resolveClientKey(request);

        // Structured log — every field on its own key=value
        // Easy to parse with log aggregation tools (ELK, Grafana Loki)
        log.warn("[SHADOW] Would have blocked | " +
                        "client={} | path={} | method={} | " +
                        "status={} | reason={} | blockedBy={} | " +
                        "retryAfter={} | timestamp={}",
                clientKey,
                request.getPath(),
                request.getMethod(),
                wouldHaveBlocked.getStatusCode(),
                wouldHaveBlocked.getReason(),
                wouldHaveBlocked.getBlockedBy(),
                wouldHaveBlocked.getRetryAfterSeconds(),
                Instant.now());

        metrics.recordShadowOverride(resolveClientKey(request));
    }


    private String resolveClientKey(GatewayRequest request) {
        ClientIdentity identity = (ClientIdentity) request
                .getRawRequest()
                .getAttribute("clientIdentity");
        return identity != null
                ? identity.getRateLimitKey()
                : "ip:" + request.getRemoteIp();
    }
}