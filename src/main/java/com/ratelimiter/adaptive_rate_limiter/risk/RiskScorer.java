package com.ratelimiter.adaptive_rate_limiter.risk;

import com.ratelimiter.adaptive_rate_limiter.model.GatewayRequest;

public interface RiskScorer {
    RiskScore score(GatewayRequest request);
}