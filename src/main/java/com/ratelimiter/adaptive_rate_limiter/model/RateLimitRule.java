package com.ratelimiter.adaptive_rate_limiter.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class RateLimitRule {

    private String id;
    private String clientKey;
    private String pathPattern;
    private int requestsPerWindow;
    private int windowSizeSeconds;

    @Builder.Default
    private int burstCapacity = 0;

    @Builder.Default
    private Algorithm algorithm = Algorithm.TOKEN_BUCKET;

    @Builder.Default
    private boolean shadowMode = false;

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private Instant createdAt = Instant.now();

    private String description;

    public enum Algorithm {
        TOKEN_BUCKET,
        SLIDING_WINDOW
    }

    public static RateLimitRule defaultFreeRule() {
        return RateLimitRule.builder()
                .id("default-free")
                .clientKey("*")
                .pathPattern("*")
                .requestsPerWindow(60)
                .windowSizeSeconds(60)
                .burstCapacity(10)
                .algorithm(Algorithm.TOKEN_BUCKET)
                .description("Default rule for FREE tier clients")
                .build();
    }
}