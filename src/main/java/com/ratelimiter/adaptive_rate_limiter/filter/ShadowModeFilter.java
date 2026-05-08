package com.ratelimiter.adaptive_rate_limiter.filter;

import com.ratelimiter.adaptive_rate_limiter.config.GatewayProperties;
import com.ratelimiter.adaptive_rate_limiter.model.GatewayRequest;
import com.ratelimiter.adaptive_rate_limiter.model.GatewayResponse;
import com.ratelimiter.adaptive_rate_limiter.shadow.ShadowModeEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Global shadow mode gate.
 * Runs last in chain @Order(4).
 *
 * Only activates when gateway.shadow-mode.enabled=true.
 *
 * Reads the rate limit decision stored by RateLimitFilter.
 * If that decision was a block → evaluateGlobal overrides
 * it to allow + logs it.
 *
 * If decision was already allowed → does nothing.
 * If global shadow mode is off   → does nothing.
 */
@Slf4j
@Component
@Order(4)
@RequiredArgsConstructor
public class ShadowModeFilter implements GatewayFilter {

    private final GatewayProperties   gatewayProperties;
    private final ShadowModeEvaluator shadowModeEvaluator;

    @Override
    public GatewayResponse filter(GatewayRequest request) {

        // Global shadow mode off — do nothing
        if (!gatewayProperties.getShadowMode().isEnabled()) {
            return GatewayResponse.allowed();
        }

        // Read the decision RateLimitFilter stored
        GatewayResponse storedDecision = (GatewayResponse) request
                .getRawRequest()
                .getAttribute("rateLimitDecision");

        // RateLimitFilter didn't run or stored null — allow
        if (storedDecision == null) {
            return GatewayResponse.allowed();
        }

        // Pass to evaluateGlobal:
        //   allowed decision → returns allowed, nothing logged
        //   blocked decision → logs + returns allowed
        return shadowModeEvaluator.evaluateGlobal(request, storedDecision);
    }

    @Override
    public String name() {
        return "ShadowModeFilter";
    }
}