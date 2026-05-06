package com.ratelimiter.adaptive_rate_limiter.model;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GatewayResponse {

    private final boolean allowed;
    private final int statusCode;
    private final String reason;
    private final Long retryAfterSeconds;
    private final String blockedBy;

    public static GatewayResponse allowed() {
        return GatewayResponse.builder()
                .allowed(true)
                .statusCode(200)
                .build();
    }

    public static GatewayResponse rateLimitExceeded(long retryAfterSeconds) {
        return GatewayResponse.builder()
                .allowed(false)
                .statusCode(429)
                .reason("Rate limit exceeded. Too many requests.")
                .retryAfterSeconds(retryAfterSeconds)
                .blockedBy("RateLimitFilter")
                .build();
    }

    public static GatewayResponse unauthorized(String reason) {
        return GatewayResponse.builder()
                .allowed(false)
                .statusCode(401)
                .reason(reason)
                .blockedBy("AuthenticationFilter")
                .build();
    }

    public static GatewayResponse circuitOpen() {
        return GatewayResponse.builder()
                .allowed(false)
                .statusCode(503)
                .reason("Service temporarily unavailable. Circuit breaker is open.")
                .retryAfterSeconds(30L)
                .blockedBy("CircuitBreakerFilter")
                .build();
    }
}