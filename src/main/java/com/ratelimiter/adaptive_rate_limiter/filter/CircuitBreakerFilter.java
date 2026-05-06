package com.ratelimiter.adaptive_rate_limiter.filter;

import com.ratelimiter.adaptive_rate_limiter.model.GatewayRequest;
import com.ratelimiter.adaptive_rate_limiter.model.GatewayResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs third in the chain (@Order 3).
 *
 * Checks the circuit breaker state before forwarding to downstream.
 * If the circuit is OPEN (downstream is failing), block immediately
 * with 503 — don't waste time trying a request that will fail.
 *
 * States:
 *   CLOSED  → everything normal, requests flow through
 *   OPEN    → downstream failing, requests blocked immediately
 *   HALF_OPEN → testing recovery, a few requests let through
 */
@Slf4j
@Component
@Order(3)
public class CircuitBreakerFilter implements GatewayFilter {

    private final CircuitBreaker circuitBreaker;

    public CircuitBreakerFilter(CircuitBreakerRegistry registry) {
        // "downstream" matches the name in application.properties:
        // resilience4j.circuitbreaker.instances.downstream.*
        this.circuitBreaker = registry.circuitBreaker("downstream");
    }

    @Override
    public GatewayResponse filter(GatewayRequest request) {

        CircuitBreaker.State state = circuitBreaker.getState();

        if (state == CircuitBreaker.State.OPEN) {
            log.warn("CircuitBreaker OPEN — blocking request to path={}",
                    request.getPath());
            return GatewayResponse.circuitOpen();
        }

        if (state == CircuitBreaker.State.HALF_OPEN) {
            log.info("CircuitBreaker HALF_OPEN — allowing test request to path={}",
                    request.getPath());
        }

        return GatewayResponse.allowed();
    }

    @Override
    public String name() {
        return "CircuitBreakerFilter";
    }
}