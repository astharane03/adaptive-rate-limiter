package com.ratelimiter.adaptive_rate_limiter.filter;

import com.ratelimiter.adaptive_rate_limiter.model.GatewayRequest;
import com.ratelimiter.adaptive_rate_limiter.model.GatewayResponse;
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
public class ShadowModeFilter implements GatewayFilter {

    @Override
    public GatewayResponse filter(GatewayRequest request) {
        // Phase 6 implementation goes here
        return GatewayResponse.allowed();
    }

    @Override
    public String name() {
        return "ShadowModeFilter";
    }
}