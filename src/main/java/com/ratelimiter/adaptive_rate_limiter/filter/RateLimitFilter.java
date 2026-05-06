package com.ratelimiter.adaptive_rate_limiter.filter;

import com.ratelimiter.adaptive_rate_limiter.config.GatewayProperties;
import com.ratelimiter.adaptive_rate_limiter.model.ClientIdentity;
import com.ratelimiter.adaptive_rate_limiter.model.GatewayRequest;
import com.ratelimiter.adaptive_rate_limiter.model.GatewayResponse;
import com.ratelimiter.adaptive_rate_limiter.model.RateLimitRule;
import com.ratelimiter.adaptive_rate_limiter.ratelimit.RateLimitResult;
import com.ratelimiter.adaptive_rate_limiter.ratelimit.RateLimiterFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs second in the chain (@Order 2).
 *
 * Flow:
 *  1. Read clientIdentity set by AuthenticationFilter
 *  2. Build a RateLimitRule based on client tier
 *  3. Ask RateLimiterFactory which algorithm to use
 *  4. Call tryAcquire() on the limiter
 *  5. Allow or block based on result
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class RateLimitFilter implements GatewayFilter {

    private final RateLimiterFactory rateLimiterFactory;
    private final GatewayProperties gatewayProperties;

    @Override
    public GatewayResponse filter(GatewayRequest request) {

        ClientIdentity identity = (ClientIdentity) request
                .getRawRequest()
                .getAttribute("clientIdentity");

        if (identity == null) {
            identity = ClientIdentity.anonymous(request.getRemoteIp());
        }

        RateLimitRule rule = buildRule(identity);

        if (!rule.isEnabled()) {
            return GatewayResponse.allowed();
        }

        // Call the rate limiter (token bucket or sliding window)
        RateLimitResult result = rateLimiterFactory
                .getLimiter(rule)
                .tryAcquire(identity.getRateLimitKey(), rule);

        log.info("RateLimit | client={} | path={} | allowed={} | remaining={}/{}",
                identity.getRateLimitKey(),
                request.getPath(),
                result.isAllowed(),
                result.getRemaining(),
                result.getLimit());

        if (result.isAllowed()) {
            return GatewayResponse.allowed();
        } else {
            return GatewayResponse.rateLimitExceeded(result.getRetryAfterSeconds());
        }
    }

    @Override
    public String name() {
        return "RateLimitFilter";
    }

    /**
     * Builds a RateLimitRule based on the client's tier.
     *
     * In Phase 8 (AdminController) we'll load rules from Redis
     * so they can be changed at runtime without restarting.
     * For now, we derive the rule from the tier + application.properties.
     */
    private RateLimitRule buildRule(ClientIdentity identity) {

        int requestsPerMinute = switch (identity.getTier()) {
            case FREE     -> gatewayProperties.getDefaultRules().getRequestsPerMinute();
            case STANDARD -> gatewayProperties.getDefaultRules().getRequestsPerMinute() * 5;
            case PREMIUM  -> gatewayProperties.getDefaultRules().getRequestsPerMinute() * 50;
        };

        String algorithmStr = gatewayProperties.getDefaultRules().getAlgorithm();
        RateLimitRule.Algorithm algorithm = RateLimitRule.Algorithm.valueOf(algorithmStr);

        return RateLimitRule.builder()
                .id("rule-" + identity.getTier().name().toLowerCase())
                .clientKey(identity.getRateLimitKey())
                .pathPattern("*")
                .requestsPerWindow(requestsPerMinute)
                .windowSizeSeconds(60)
                .burstCapacity(gatewayProperties.getDefaultRules().getBurstCapacity())
                .algorithm(algorithm)
                .enabled(true)
                .build();
    }
}