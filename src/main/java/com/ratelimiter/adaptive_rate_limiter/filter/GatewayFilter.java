package com.ratelimiter.adaptive_rate_limiter.filter;

import com.ratelimiter.adaptive_rate_limiter.model.GatewayRequest;
import com.ratelimiter.adaptive_rate_limiter.model.GatewayResponse;

/**
 * Every filter in the chain implements this interface.
 *
 * Contract:
 *   - Return GatewayResponse.allowed() → request moves to next filter
 *   - Return anything else → chain stops, that response goes to the client
 *
 * Filters run in order controlled by @Order annotation on each class.
 * Lower number = runs first.
 *   AuthenticationFilter  @Order(1)
 *   RateLimitFilter       @Order(2)
 *   CircuitBreakerFilter  @Order(3)
 */
public interface GatewayFilter {

    GatewayResponse filter(GatewayRequest request);

    String name();
}