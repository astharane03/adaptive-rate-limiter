package com.ratelimiter.adaptive_rate_limiter.risk;

import lombok.Builder;
import lombok.Getter;

/**
 * The computed risk score for a client at a point in time.
 *
 * Score: 0.0 = perfectly normal, 1.0 = maximum risk
 *
 * Throttle multiplier = 1.0 - score
 * Example:
 *   score = 0.7 → multiplier = 0.3 → client gets 30% of base limit
 *   score = 0.0 → multiplier = 1.0 → client gets 100% of base limit
 */
@Getter
@Builder
public class RiskScore {

    private final double score;
    private final double burstRatio;
    private final double errorRate;
    private final String reason;

    public static RiskScore noRisk() {
        return RiskScore.builder()
                .score(0.0)
                .burstRatio(0.0)
                .errorRate(0.0)
                .reason("Normal behavior")
                .build();
    }

    public boolean isHighRisk()   { return score >= 0.7; }
    public boolean isMediumRisk() { return score >= 0.4 && score < 0.7; }
    public boolean isLowRisk()    { return score < 0.4; }

    /**
     * The multiplier applied to the base rate limit.
     * Floor of 0.1 ensures we never fully block a client
     * via risk score alone — that's the rate limiter's job.
     */
    public double getThrottleMultiplier() {
        return Math.max(0.1, 1.0 - score);
    }

    @Override
    public String toString() {
        return String.format("RiskScore{score=%.2f, burst=%.2f, errorRate=%.2f, reason='%s'}",
                score, burstRatio, errorRate, reason);
    }
}