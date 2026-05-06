package com.ratelimiter.adaptive_rate_limiter.model;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.*;

@Getter
@Builder
@ToString(exclude = "rawRequest")
public class GatewayRequest {

    private final ClientIdentity clientIdentity;
    private final String method;
    private final String path;
    private final String queryString;
    private final Map<String, String> headers;
    private final String remoteIp;
    private final Instant arrivedAt;
    private final HttpServletRequest rawRequest;

    /**
     * Called at the very entry point of the gateway — converts
     * the raw servlet request into our own clean model.
     */
    public static GatewayRequest from(HttpServletRequest request) {
        return GatewayRequest.builder()
                .method(request.getMethod())
                .path(request.getRequestURI())
                .queryString(request.getQueryString())
                .headers(extractHeaders(request))
                .remoteIp(extractClientIp(request))
                .arrivedAt(Instant.now())
                .rawRequest(request)
                .build();
    }

    /**
     * Returns a new GatewayRequest with clientIdentity set.
     * Called by AuthenticationFilter after resolving who the client is.
     */
    public GatewayRequest withClientIdentity(ClientIdentity identity) {
        return GatewayRequest.builder()
                .clientIdentity(identity)
                .method(this.method)
                .path(this.path)
                .queryString(this.queryString)
                .headers(this.headers)
                .remoteIp(this.remoteIp)
                .arrivedAt(this.arrivedAt)
                .rawRequest(this.rawRequest)
                .build();
    }

    public String getHeader(String name) {
        return headers.getOrDefault(name.toLowerCase(), null);
    }

    public String getFullPath() {
        return queryString != null ? path + "?" + queryString : path;
    }

    private static Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                headers.put(name.toLowerCase(), request.getHeader(name));
            }
        }
        return Collections.unmodifiableMap(headers);
    }

    /**
     * Always check X-Forwarded-For first.
     * Behind a load balancer, getRemoteAddr() returns the LB's IP,
     * not the actual client. X-Forwarded-For has the real client IP.
     */
    private static String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}