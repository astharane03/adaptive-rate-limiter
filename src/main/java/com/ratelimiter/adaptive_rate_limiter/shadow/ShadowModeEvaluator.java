package com.ratelimiter.adaptive_rate_limiter.shadow;

import com.ratelimiter.adaptive_rate_limiter.model.GatewayRequest;
import com.ratelimiter.adaptive_rate_limiter.model.GatewayResponse;
import com.ratelimiter.adaptive_rate_limiter.model.RateLimitRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Decides whether to enforce a rate limit decision or
 * just log it (shadow mode).
 *
 * Called by RateLimitFilter after getting a result
 * from the rate limiter.
 *
 * Two inputs:
 *   rule     — contains shadowMode flag
 *   decision — the GatewayResponse the limiter produced
 *
 * Two outputs:
 *   If rule.shadowMode = false → return decision as-is (enforce)
 *   If rule.shadowMode = true  → log + return allowed (don't enforce)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShadowModeEvaluator {

    private final ShadowDecisionLogger shadowDecisionLogger;

    public GatewayResponse evaluate(GatewayRequest request,
                                    GatewayResponse decision,
                                    RateLimitRule rule) {

        // Rule is not in shadow mode — enforce normally
        if (!rule.isShadowMode()) {
            return decision;
        }

        // Rule IS in shadow mode
        // If the decision was to allow — nothing special, just allow
        if (decision.isAllowed()) {
            return decision;
        }

        // Decision was to BLOCK but shadow mode is on
        // Log what would have happened, but let the request through
        shadowDecisionLogger.logWouldHaveBlocked(request, decision);

        log.info("[SHADOW] Overriding block → allowing | " +
                        "client={} | path={}",
                resolveClientKey(request),
                request.getPath());

        return GatewayResponse.allowed();
    }

    /**
     * Global shadow mode check — used by ShadowModeFilter.
     * If globally enabled, ALL blocking decisions become log-only.
     */
    public GatewayResponse evaluateGlobal(GatewayRequest request,
                                          GatewayResponse decision,
                                          boolean globalShadowEnabled) {
        if (!globalShadowEnabled || decision.isAllowed()) {
            return decision;
        }

        shadowDecisionLogger.logWouldHaveBlocked(request, decision);
        return GatewayResponse.allowed();
    }

    private String resolveClientKey(GatewayRequest request) {
        Object identity = request.getRawRequest()
                .getAttribute("clientIdentity");
        if (identity instanceof
                com.ratelimiter.adaptive_rate_limiter.model.ClientIdentity ci) {
            return ci.getRateLimitKey();
        }
        return "ip:" + request.getRemoteIp();
    }
}