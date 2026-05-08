package com.ratelimiter.adaptive_rate_limiter.filter;

import com.ratelimiter.adaptive_rate_limiter.config.GatewayProperties;
import com.ratelimiter.adaptive_rate_limiter.model.ClientIdentity;
import com.ratelimiter.adaptive_rate_limiter.model.GatewayRequest;
import com.ratelimiter.adaptive_rate_limiter.model.GatewayResponse;
import com.ratelimiter.adaptive_rate_limiter.model.RateLimitRule;
import com.ratelimiter.adaptive_rate_limiter.ratelimit.RateLimitResult;
import com.ratelimiter.adaptive_rate_limiter.ratelimit.RateLimiterFactory;
import com.ratelimiter.adaptive_rate_limiter.risk.ClientBehaviorTracker;
import com.ratelimiter.adaptive_rate_limiter.risk.CompositeRiskScorer;
import com.ratelimiter.adaptive_rate_limiter.risk.RiskScore;
import com.ratelimiter.adaptive_rate_limiter.shadow.ShadowModeEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * HOW THIS FILTER WORKS — THE FULL LOGIC
 *
 * ─────────────────────────────────────────────────────────────
 * OVERVIEW
 * ─────────────────────────────────────────────────────────────
 * This filter does two things:
 *   1. Decides if a client is allowed to make a request RIGHT NOW
 *   2. Adapts that decision based on how the client has behaved RECENTLY
 *
 * A normal rate limiter gives every client the same fixed limit.
 * This filter is ADAPTIVE — a well-behaved client gets their full limit,
 * a misbehaving client gets automatically throttled without any human
 * changing any config.
 *
 * ─────────────────────────────────────────────────────────────
 * THE 5 STEPS — what happens for every single request
 * ─────────────────────────────────────────────────────────────
 *
 * STEP 1 — WHO IS THIS CLIENT?
 * ─────────────────────────────
 * AuthenticationFilter ran before us (@Order 1, we are @Order 2).
 * It resolved the client's identity from the x-api-key header and stored
 * a ClientIdentity object in the servlet request attribute "clientIdentity".
 * We read it here.
 *
 * ClientIdentity tells us:
 *   - rateLimitKey : the Redis key prefix e.g. "client:abc123" or "ip:1.2.3.4"
 *   - tier         : FREE / STANDARD / PREMIUM → determines base limit
 *
 * If the attribute is missing (auth filter somehow skipped), we fall back
 * to anonymous identity using the client's IP address.
 *
 *
 * STEP 2 — RECORD THIS REQUEST IN THE BURST TRACKER
 * ───────────────────────────────────────────────────
 * We call behaviorTracker.recordRequest(clientKey).
 *
 * What this does in Redis:
 *   ZADD risk:burst:client:abc123 {nowMs} "{nowMs}-{random}"
 *   ZREMRANGEBYSCORE risk:burst:client:abc123 0 {nowMs - 10000}
 *
 * Translation:
 *   - Add the current timestamp as an entry in a sorted set
 *   - Remove all entries older than 10 seconds
 *
 * Result: the sorted set always holds ONLY the timestamps of requests
 * made in the last 10 seconds. Nothing older. Nothing younger.
 *
 * WHY do we record BEFORE scoring?
 * Because the burst ratio for THIS request should include THIS request.
 * If we scored first, this request's timestamp wouldn't be in Redis yet
 * and the burst signal would be one request behind.
 *
 *
 * STEP 3 — COMPUTE THE RISK SCORE
 * ─────────────────────────────────
 * We call riskScorer.score(request) which reads two signals from Redis:
 *
 * Signal A — BURST RATIO (weight: 60%)
 * "Is this client firing requests faster than normal?"
 *
 *   burstRatio = (requests in last 10 seconds) / BURST_THRESHOLD(20)
 *
 *   Example: 8 requests in last 10 seconds → 8/20 = 0.40
 *   Example: 20+ requests in last 10 seconds → capped at 1.0
 *
 *   This signal is CURRENT — it includes the request we just recorded
 *   in Step 2. Zero lag.
 *
 * Signal B — ERROR RATE (weight: 40%)
 * "Has this client been getting lots of error responses recently?"
 *
 *   errorRate = (errors in last 60 seconds) / ERROR_THRESHOLD(10)
 *
 *   An "error" is recorded by GatewayController AFTER the downstream
 *   response comes back as 4xx or 5xx. That means:
 *
 *   ┌─────────────────────────────────────────────────────────┐
 *   │  Request N arrives                                      │
 *   │    → Step 2: recordRequest() — burst signal updated NOW │
 *   │    → Step 3: score() — burst seen, errors NOT yet seen  │
 *   │    → Request forwarded to downstream                    │
 *   │    → Downstream returns 502                             │
 *   │    → GatewayController calls recordError()              │
 *   │                                                         │
 *   │  Request N+1 arrives                                    │
 *   │    → Step 3: score() — NOW sees the error from N        │
 *   └─────────────────────────────────────────────────────────┘
 *
 *   This means error rate is ALWAYS one request behind.
 *   This is intentional and correct — risk scoring is about detecting
 *   patterns over time, not punishing a single bad request.
 *   By request 5 or 6, the error pattern is clear and throttling kicks in.
 *
 * Combining the two signals:
 *   compositeScore = (burstRatio * 0.6) + (errorRate * 0.4)
 *
 *   Examples:
 *     burstRatio=0.0, errorRate=0.0  → score=0.00 → multiplier=1.00 → full limit
 *     burstRatio=0.5, errorRate=0.0  → score=0.30 → multiplier=0.70 → 70% of limit
 *     burstRatio=0.0, errorRate=0.5  → score=0.20 → multiplier=0.80 → 80% of limit
 *     burstRatio=1.0, errorRate=1.0  → score=1.00 → multiplier=0.10 → 10% of limit
 *
 *   Floor of 0.10 on the multiplier means we never reduce to zero via
 *   risk scoring alone. Complete blocking is the rate limiter's job.
 *
 *
 * STEP 4 — BUILD THE ADAPTIVE RATE LIMIT RULE
 * ─────────────────────────────────────────────
 * Base limit comes from the client's tier + application.properties:
 *   FREE     →  60 req/min  (gateway.default-rules.requests-per-minute)
 *   STANDARD →  300 req/min (base * 5)
 *   PREMIUM  →  3000 req/min (base * 50)
 *
 * Effective limit = baseLimit * throttleMultiplier
 *
 *   Example: FREE client, score=0.7
 *   effectiveLimit = 60 * (1.0 - 0.7) = 60 * 0.3 = 18 req/min
 *
 * High risk clients (score >= 0.7) also get burstCapacity = 0.
 * Normal clients get burstCapacity from application.properties (default 10).
 * This means high risk clients cannot use any burst headroom at all.
 *
 *
 * STEP 5 — CALL THE RATE LIMITER (LUA IN REDIS)
 * ───────────────────────────────────────────────
 * We call rateLimiterFactory.getLimiter(rule).tryAcquire(clientKey, rule).
 *
 * This executes either token_bucket.lua or sliding_window.lua atomically
 * in Redis. The Lua script:
 *   1. Reads current token count and last refill timestamp from Redis Hash
 *   2. Calculates tokens earned since last request (elapsed * refillRate)
 *   3. Caps tokens at effectiveLimit (math.min — trims bucket to new ceiling)
 *   4. If tokens >= 1: consume 1 token, return allowed=1
 *   5. If tokens < 1: return allowed=0, retry_after in seconds
 *
 * IMPORTANT — understanding "remaining=X/Y" in the logs:
 *   X = actual tokens in the Redis bucket right now
 *   Y = effectiveLimit calculated from the risk score
 *
 *   X can be GREATER than Y. This happens when:
 *   - Client had a full bucket (say 60 tokens) under a low risk score
 *   - Then burst traffic raised their risk score
 *   - New effectiveLimit drops to 18
 *   - But the bucket still physically has 45 tokens from before
 *
 *   The bucket drains DOWN to the new ceiling naturally over time
 *   as the client keeps making requests. math.min() in the Lua script
 *   prevents the bucket from ever refilling ABOVE the new ceiling.
 *   So X converges toward Y from above as requests continue.
 *
 * ─────────────────────────────────────────────────────────────
 * WHAT LIVES IN REDIS after this filter runs
 * ─────────────────────────────────────────────────────────────
 *
 *   rl:tb:client:abc123          ← token bucket (Hash)
 *     tokens      = "47.3"       ← current token count
 *     last_refill = "1778096.."  ← timestamp of last request in ms
 *
 *   risk:burst:client:abc123     ← burst tracker (Sorted Set)
 *     members = request timestamps in last 10 seconds
 *     score   = timestamp in ms (enables ZREMRANGEBYSCORE cleanup)
 *
 *   risk:errors:client:abc123    ← error tracker (Sorted Set)
 *     members = error timestamps in last 60 seconds
 *     written by GatewayController AFTER downstream responds with error
 *
 * ─────────────────────────────────────────────────────────────
 * THE FULL TIMELINE — two consecutive requests
 * ─────────────────────────────────────────────────────────────
 *
 *  t=0ms   Request 1 arrives
 *            Step 1: identity = client:abc123 (FREE)
 *            Step 2: recordRequest() → risk:burst now has 1 entry
 *            Step 3: burstRatio=0.05, errorRate=0.0 → score=0.03
 *            Step 4: effectiveLimit = 60 * 0.97 = 58
 *            Step 5: Lua runs → 57 tokens remain → ALLOWED
 *
 *  t=1ms   Request 1 forwarded to downstream
 *
 *  t=50ms  Downstream returns 502
 *            GatewayController: recordError() → risk:errors now has 1 entry
 *
 *  t=100ms Request 2 arrives
 *            Step 1: identity = client:abc123 (FREE)
 *            Step 2: recordRequest() → risk:burst now has 2 entries
 *            Step 3: burstRatio=0.10, errorRate=0.10 → score=0.10
 *            Step 4: effectiveLimit = 60 * 0.90 = 54
 *            Step 5: Lua runs → 53 tokens remain → ALLOWED
 *                    (the error from request 1 is now visible here)
 *
 * ─────────────────────────────────────────────────────────────
 * SUMMARY — why the one-request lag on errors is correct
 * ─────────────────────────────────────────────────────────────
 * Risk scoring detects PATTERNS, not individual events.
 * A single 502 might be a fluke — network blip, downstream restart.
 * 8 errors in 60 seconds is a clear pattern — broken integration,
 * bad API key, hammering a wrong endpoint.
 * The one-request lag means a single bad response never wrongly
 * throttles a client. The pattern has to repeat before scoring reacts.
 */

@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class RateLimitFilter implements GatewayFilter {

    private final RateLimiterFactory    rateLimiterFactory;
    private final GatewayProperties     gatewayProperties;
    private final CompositeRiskScorer riskScorer;
    private final ClientBehaviorTracker behaviorTracker;
    private final ShadowModeEvaluator   shadowModeEvaluator;

    @Override
    public GatewayResponse filter(GatewayRequest request) {

        // Step 1 — resolve client identity
        ClientIdentity identity = (ClientIdentity) request
                .getRawRequest()
                .getAttribute("clientIdentity");

        if (identity == null) {
            identity = ClientIdentity.anonymous(request.getRemoteIp());
        }

        // Step 2 — record request for behavior tracking
        behaviorTracker.recordRequest(identity.getRateLimitKey());

        // Step 3 — compute risk score
        RiskScore riskScore = riskScorer.score(request);

        // Step 4 — build adaptive rule
        RateLimitRule rule = buildAdaptiveRule(identity, riskScore);

        if (!rule.isEnabled()) {
            return GatewayResponse.allowed();
        }

        // Step 5 — call the rate limiter
        RateLimitResult result = rateLimiterFactory
                .getLimiter(rule)
                .tryAcquire(identity.getRateLimitKey(), rule);

        log.info("RateLimit | client={} | path={} | allowed={} | " +
                        "remaining={}/{} | riskScore={} | multiplier={}",
                identity.getRateLimitKey(),
                request.getPath(),
                result.isAllowed(),
                result.getRemaining(),
                result.getLimit(),
                String.format("%.2f", riskScore.getScore()),
                String.format("%.2f", riskScore.getThrottleMultiplier()));

        // Step 6 — raw decision
        GatewayResponse decision = result.isAllowed()
                ? GatewayResponse.allowed()
                : GatewayResponse.rateLimitExceeded(
                result.getRetryAfterSeconds());

        // Step 7 — shadow mode check
        return shadowModeEvaluator.evaluate(request, decision, rule);
    }

    @Override
    public String name() {
        return "RateLimitFilter";
    }

    private RateLimitRule buildAdaptiveRule(ClientIdentity identity,
                                            RiskScore riskScore) {
        int baseLimit = switch (identity.getTier()) {
            case FREE     -> gatewayProperties
                    .getDefaultRules().getRequestsPerMinute();
            case STANDARD -> gatewayProperties
                    .getDefaultRules().getRequestsPerMinute() * 5;
            case PREMIUM  -> gatewayProperties
                    .getDefaultRules().getRequestsPerMinute() * 50;
        };

        int effectiveLimit = (int) Math.max(1,
                baseLimit * riskScore.getThrottleMultiplier());

        RateLimitRule.Algorithm algorithm = RateLimitRule.Algorithm.valueOf(
                gatewayProperties.getDefaultRules().getAlgorithm());

        if (riskScore.isMediumRisk() || riskScore.isHighRisk()) {
            log.info("Adaptive throttle | client={} | base={} | effective={} | reason={}",
                    identity.getRateLimitKey(),
                    baseLimit, effectiveLimit,
                    riskScore.getReason());
        }

        return RateLimitRule.builder()
                .id("adaptive-" + identity.getTier().name().toLowerCase())
                .clientKey(identity.getRateLimitKey())
                .pathPattern("*")
                .requestsPerWindow(effectiveLimit)
                .windowSizeSeconds(60)
                .burstCapacity(riskScore.isHighRisk() ? 0
                        : gatewayProperties.getDefaultRules().getBurstCapacity())
                .algorithm(algorithm)
                .shadowMode(false)
                .enabled(true)
                .build();
    }
}