package com.ratelimiter.adaptive_rate_limiter.filter;

import com.ratelimiter.adaptive_rate_limiter.model.ClientIdentity;
import com.ratelimiter.adaptive_rate_limiter.model.GatewayRequest;
import com.ratelimiter.adaptive_rate_limiter.model.GatewayResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs first in the chain (@Order 1).
 *
 * Resolves WHO is making the request:
 *   - If X-Api-Key header is present → authenticated client
 *   - If no header → anonymous client identified by IP
 *
 * NOTE: In a real production system this filter would validate
 * the API key against a database or cache. For this project
 * we trust any non-empty key — the focus is rate limiting, not auth.
 *
 * This filter never blocks a request — anonymous traffic is allowed
 * but gets a stricter rate limit (FREE tier).
 */
@Slf4j
@Component
@Order(1)
public class AuthenticationFilter implements GatewayFilter {

    private static final String API_KEY_HEADER = "x-api-key";

    @Override
    public GatewayResponse filter(GatewayRequest request) {
        String apiKey = request.getHeader(API_KEY_HEADER);

        ClientIdentity identity;

        if (apiKey != null && !apiKey.isBlank()) {
            // Authenticated client — key present
            identity = ClientIdentity.builder()
                    .apiKey(apiKey)
                    .clientName("client-" + apiKey)
                    .tier(resolveTier(apiKey))
                    .ipAddress(request.getRemoteIp())
                    .build();

            log.debug("Auth | AUTHENTICATED | key={} | tier={}",
                    apiKey, identity.getTier());
        } else {
            // Anonymous client — IP-based limiting
            identity = ClientIdentity.anonymous(request.getRemoteIp());

            log.debug("Auth | ANONYMOUS | ip={}", request.getRemoteIp());
        }

        // The RateLimitFilter reads it from there

        //request — our immutable GatewayRequest wrapper.
        // Its own fields (path, method, headers) are frozen and can never change.
        //.getRawRequest() — reaches inside and pulls out the HttpServletRequest object that Tomcat created.
        // The reference to it is final
        // — meaning we always get the same Tomcat object
        // — but the Tomcat object itself is mutable.
        //.setAttribute("clientIdentity", identity)
        // — writes into HttpServletRequest's built-in Map<String, Object>.
        // This map exists for exactly this purpose
        // — passing data between components during one request's lifetime.

        // The RateLimitFilter reads it from there
        request.getRawRequest().setAttribute("clientIdentity", identity);

        return GatewayResponse.allowed();
    }

    @Override
    public String name() {
        return "AuthenticationFilter";
    }

    /**
     * In production this would be a database lookup.
     * For now: keys starting with "premium-" get PREMIUM tier,
     * keys starting with "std-" get STANDARD, everything else FREE.
     */
    private ClientIdentity.ClientTier resolveTier(String apiKey) {
        if (apiKey.startsWith("premium-")) {
            return ClientIdentity.ClientTier.PREMIUM;
        } else if (apiKey.startsWith("std-")) {
            return ClientIdentity.ClientTier.STANDARD;
        }
        return ClientIdentity.ClientTier.FREE;
    }
}