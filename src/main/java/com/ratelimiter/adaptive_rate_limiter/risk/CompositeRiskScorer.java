package com.ratelimiter.adaptive_rate_limiter.risk;

import com.ratelimiter.adaptive_rate_limiter.model.ClientIdentity;
import com.ratelimiter.adaptive_rate_limiter.model.GatewayRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Combines burst ratio and error rate into a single risk score.
 *
 * Weights:
 *   Burst ratio contributes 60% of the score
 *   Error rate contributes 40% of the score
 *
 * Why these weights?
 * Burst behavior is a stronger signal of abuse than errors.
 * A legitimate client might have a burst of errors during
 * a bad deploy — but sustained burst traffic is almost
 * always intentional.
 *
 * Formula:
 *   score = (burstRatio * 0.6) + (errorRate * 0.4)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompositeRiskScorer implements RiskScorer {

    private static final double BURST_WEIGHT = 0.6;
    private static final double ERROR_WEIGHT = 0.4;

    private final ClientBehaviorTracker behaviorTracker;

    @Override
    public RiskScore score(GatewayRequest request) {
        String clientKey = resolveClientKey(request);

        double burstRatio = behaviorTracker.getBurstRatio(clientKey);
        double errorRate  = behaviorTracker.getErrorRate(clientKey);

        // Weighted composite score
        double compositeScore = (burstRatio * BURST_WEIGHT)
                + (errorRate  * ERROR_WEIGHT);

        // Clamp to [0.0, 1.0]
        compositeScore = Math.min(1.0, Math.max(0.0, compositeScore));

        String reason = buildReason(burstRatio, errorRate, compositeScore);

        RiskScore riskScore = RiskScore.builder()
                .score(compositeScore)
                .burstRatio(burstRatio)
                .errorRate(errorRate)
                .reason(reason)
                .build();

        if (riskScore.isHighRisk()) {
            log.warn("HIGH RISK | client={} | {}", clientKey, riskScore);
        } else if (riskScore.isMediumRisk()) {
            log.info("MEDIUM RISK | client={} | {}", clientKey, riskScore);
        }

        return riskScore;
    }

    private String resolveClientKey(GatewayRequest request) {
        ClientIdentity identity = (ClientIdentity) request
                .getRawRequest()
                .getAttribute("clientIdentity");
        if (identity != null) {
            return identity.getRateLimitKey();
        }
        return "ip:" + request.getRemoteIp();
    }

    private String buildReason(double burstRatio,
                               double errorRate,
                               double score) {
        if (score < 0.1)  return "Normal behavior";
        if (burstRatio > errorRate) {
            return String.format("Burst traffic detected (%.0f%% of burst threshold)",
                    burstRatio * 100);
        }
        return String.format("High error rate (%.0f%% of error threshold)",
                errorRate * 100);
    }
}