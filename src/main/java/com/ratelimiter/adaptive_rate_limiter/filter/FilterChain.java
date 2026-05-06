package com.ratelimiter.adaptive_rate_limiter.filter;

import com.ratelimiter.adaptive_rate_limiter.model.GatewayRequest;
import com.ratelimiter.adaptive_rate_limiter.model.GatewayResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runs every filter in order.
 * Stops immediately when any filter blocks the request.
 *
 * Spring automatically injects all beans that implement GatewayFilter
 * into the List<GatewayFilter> — ordered by @Order value.
 * You never need to manually register filters here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FilterChain {

    private final List<GatewayFilter> filters;

    public GatewayResponse execute(GatewayRequest request) {

        for (GatewayFilter filter : filters) {

            GatewayResponse response = filter.filter(request);

            if (!response.isAllowed()) {
                log.info("Request BLOCKED | filter={} | path={} | client={} | reason={}",
                        filter.name(),
                        request.getPath(),
                        resolveClientKey(request),
                        response.getReason());
                return response;
            }
        }

        log.debug("Request ALLOWED | path={} | client={}",
                request.getPath(),
                resolveClientKey(request));

        return GatewayResponse.allowed();
    }

    private String resolveClientKey(GatewayRequest request) {
        if (request.getClientIdentity() != null) {
            return request.getClientIdentity().getRateLimitKey();
        }
        return request.getRemoteIp();
    }
}