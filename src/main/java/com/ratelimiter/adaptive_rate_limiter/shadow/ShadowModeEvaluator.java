package com.ratelimiter.adaptive_rate_limiter.shadow;

import com.ratelimiter.adaptive_rate_limiter.model.GatewayRequest;
import com.ratelimiter.adaptive_rate_limiter.model.GatewayResponse;
import com.ratelimiter.adaptive_rate_limiter.model.RateLimitRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Two shadow mode scenarios:
 *
 * Scenario A — per-rule shadow mode
 *   A specific RateLimitRule has shadowMode=true.
 *   Only that rule's blocks become log-only.
 *   Called from RateLimitFilter after tryAcquire().
 *
 * Scenario B — global shadow mode
 *   gateway.shadow-mode.enabled=true in application.properties.
 *   ALL blocks from ALL filters become log-only.
 *   Called from ShadowModeFilter.
 *
 * Both scenarios use the same logger internally.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShadowModeEvaluator {

    private final ShadowDecisionLogger shadowDecisionLogger;

    /**
     * Scenario A — per-rule.
     * Called by RateLimitFilter.
     * Checks rule.isShadowMode() to decide enforce vs log-only.
     */
    public GatewayResponse evaluate(GatewayRequest request,
                                    GatewayResponse decision,
                                    RateLimitRule rule) {

        // Rule not in shadow mode — enforce normally
        if (!rule.isShadowMode()) {
            return decision;
        }

        // Rule IS in shadow mode but request was allowed anyway
        // Nothing to override — just return allowed
        if (decision.isAllowed()) {
            return decision;
        }

        // Rule IS in shadow mode AND decision was to block
        // Log it but let the request through
        shadowDecisionLogger.logWouldHaveBlocked(request, decision);
        return GatewayResponse.allowed();
    }

    /**
     * Scenario B — global.
     * Called by ShadowModeFilter.
     * Receives a decision that already came out of RateLimitFilter.
     * If that decision was a block → override to allow + log.
     */
    public GatewayResponse evaluateGlobal(GatewayRequest request,
                                          GatewayResponse decision) {

        // Request was allowed — nothing to override
        if (decision.isAllowed()) {
            return decision;
        }

        // Decision was block + global shadow is on
        // Log and let through
        shadowDecisionLogger.logWouldHaveBlocked(request, decision);
        return GatewayResponse.allowed();
    }
}