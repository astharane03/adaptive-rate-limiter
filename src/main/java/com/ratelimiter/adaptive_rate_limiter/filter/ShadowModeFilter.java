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
 * Runs fourth in the chain (@Order 4).
 *
 * Shadow mode = evaluate rules but never actually block.
 * Used when you want to test a new rule safely in production
 * without affecting real users.
 *
 * Full implementation comes in Phase 6 when we wire in
 * ShadowModeEvaluator. For now this is a pass-through.
 */
@Slf4j
@Component
@Order(4)
@RequiredArgsConstructor
public class ShadowModeFilter implements GatewayFilter {

    private final GatewayProperties gatewayProperties;
    private final ShadowModeEvaluator shadowModeEvaluator;

    @Override
    public GatewayResponse filter(GatewayRequest request) {
        boolean globalShadow = gatewayProperties.getShadowMode().isEnabled();

        if (!globalShadow) {
            // Shadow mode off globally — nothing to do
            return GatewayResponse.allowed();
        }

        // Global shadow mode is ON
        // Previous filters already ran and made their decisions.
        // We can't retroactively change them here — but we log
        // the intent and let everything through.
        log.debug("[SHADOW] Global shadow mode active | path={}",
                request.getPath());

        return GatewayResponse.allowed();
    }

    @Override
    public String name() {
        return "ShadowModeFilter";
    }
}