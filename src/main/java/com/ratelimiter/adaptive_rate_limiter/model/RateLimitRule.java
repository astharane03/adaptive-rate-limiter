package com.ratelimiter.adaptive_rate_limiter.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    /**
     * Jackson uses this constructor to deserialize JSON → RateLimitRule.
     * Every field that can come from JSON must be listed here with @JsonProperty.
     * Fields with defaults (algorithm, enabled, etc.) are Optional so Jackson
     * sends null if not provided — we then apply the default manually.
     */
    @JsonCreator
    public static RateLimitRule fromJson(
            @JsonProperty("id")                 String id,
            @JsonProperty("clientKey")          String clientKey,
            @JsonProperty("pathPattern")        String pathPattern,
            @JsonProperty("requestsPerWindow")  int requestsPerWindow,
            @JsonProperty("windowSizeSeconds")  int windowSizeSeconds,
            @JsonProperty("burstCapacity")      Integer burstCapacity,
            @JsonProperty("algorithm")          Algorithm algorithm,
            @JsonProperty("shadowMode")         Boolean shadowMode,
            @JsonProperty("enabled")            Boolean enabled,
            @JsonProperty("description")        String description
    ) {
        return RateLimitRule.builder()
                .id(id)
                .clientKey(clientKey != null ? clientKey : "*")
                .pathPattern(pathPattern != null ? pathPattern : "*")
                .requestsPerWindow(requestsPerWindow)
                .windowSizeSeconds(windowSizeSeconds)
                .burstCapacity(burstCapacity != null ? burstCapacity : 0)
                .algorithm(algorithm != null ? algorithm : Algorithm.TOKEN_BUCKET)
                .shadowMode(shadowMode != null ? shadowMode : false)
                .enabled(enabled != null ? enabled : true)
                .createdAt(Instant.now())
                .description(description)
                .build();
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
