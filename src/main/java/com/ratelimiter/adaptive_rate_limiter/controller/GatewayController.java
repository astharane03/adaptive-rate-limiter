package com.ratelimiter.adaptive_rate_limiter.controller;

import com.ratelimiter.adaptive_rate_limiter.config.GatewayProperties;
import com.ratelimiter.adaptive_rate_limiter.filter.FilterChain;
import com.ratelimiter.adaptive_rate_limiter.model.GatewayRequest;
import com.ratelimiter.adaptive_rate_limiter.model.GatewayResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The single entry point for all proxied traffic.
 *
 * @RequestMapping("/api/**") catches every request
 * under /api/ and routes it through the filter chain.
 *
 * If all filters pass → forward to downstream and return
 * its response untouched.
 *
 * If any filter blocks → return the block response
 * immediately without touching downstream.
 */
@Slf4j
@RestController
public class GatewayController {

    private final FilterChain filterChain;
    private final WebClient webClient;
    private final GatewayProperties gatewayProperties;

    public GatewayController(FilterChain filterChain,
                             GatewayProperties gatewayProperties) {
        this.filterChain = filterChain;
        this.gatewayProperties = gatewayProperties;

        // WebClient is the non-blocking HTTP client
        // We configure the base URL from application.properties
        this.webClient = WebClient.builder()
                .baseUrl(gatewayProperties.getDownstream().getBaseUrl())
                .build();
    }


    //baseurl/api/vw12e23ejo
    //service/sjdbew
    @RequestMapping("/api/**")
    public ResponseEntity<?> handle(HttpServletRequest httpRequest) {

        // Step 1 — wrap raw HTTP request into our model
        GatewayRequest request = GatewayRequest.from(httpRequest);

        log.info("Incoming | method={} | path={} | ip={}",
                request.getMethod(),
                request.getPath(),
                request.getRemoteIp());

        // Step 2 — run through filter chain
        // (Auth → RateLimit → CircuitBreaker)
        GatewayResponse filterResponse = filterChain.execute(request);

        // Step 3 — if blocked, return immediately
        if (!filterResponse.isAllowed()) {
            return buildBlockedResponse(filterResponse);
        }

        // Step 4 — forward to downstream
        return forwardToDownstream(request, httpRequest);
    }

    /**
     * Forwards the request to the downstream service and
     * returns its response back to the original caller.
     *
     * We preserve:
     *   - HTTP method (GET, POST, PUT, DELETE)
     *   - Path (strip /api prefix, downstream sees /v1/users etc.)
     *   - Request body
     *   - Response status code
     *   - Response body
     */
    private ResponseEntity<?> forwardToDownstream(GatewayRequest request,
                                                  HttpServletRequest httpRequest) {
        try {
            // Extract path after /api — forward /api/v1/users as /v1/users
            String downstreamPath = request.getPath().replaceFirst("/api", "");
            if (downstreamPath.isEmpty()) downstreamPath = "/";

            // Append query string if present
            String fullPath = request.getQueryString() != null
                    ? downstreamPath + "?" + request.getQueryString()
                    : downstreamPath;

            // Read request body
            String requestBody = null;
            try {
                requestBody = httpRequest.getReader()
                        .lines()
                        .collect(Collectors.joining());
            } catch (IOException e) {
                log.warn("Could not read request body: {}", e.getMessage());
            }

            final String body = requestBody;

            log.debug("Forwarding | method={} | downstreamPath={}",
                    request.getMethod(), fullPath);

            // Build and execute the downstream call
            ResponseEntity<String> downstreamResponse = webClient
                    .method(HttpMethod.valueOf(request.getMethod()))
                    .uri(fullPath)
                    .bodyValue(body != null && !body.isEmpty() ? body : "")
                    .retrieve()
                    .toEntity(String.class)
                    .block(); // block() converts reactive → synchronous

            if (downstreamResponse == null) {
                return ResponseEntity
                        .status(HttpStatus.BAD_GATEWAY)
                        .body(errorBody(502, "No response from downstream"));
            }

            log.info("Downstream responded | status={}",
                    downstreamResponse.getStatusCode());

            // Forward the downstream response back to the client
            return ResponseEntity
                    .status(downstreamResponse.getStatusCode())
                    .headers(sanitizeHeaders(downstreamResponse.getHeaders()))
                    .body(downstreamResponse.getBody());

        } catch (WebClientResponseException ex) {
            // Downstream returned 4xx or 5xx
            log.error("Downstream error | status={} | body={}",
                    ex.getStatusCode(), ex.getResponseBodyAsString());
            return ResponseEntity
                    .status(ex.getStatusCode())
                    .body(ex.getResponseBodyAsString());

        } catch (Exception ex) {
            log.error("Failed to forward request: {}", ex.getMessage());
            return ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(errorBody(502, "Gateway error: " + ex.getMessage()));
        }
    }

    /**
     * Converts a blocked GatewayResponse into an HTTP ResponseEntity.
     * Adds standard rate limit headers so clients know what happened.
     */
    private ResponseEntity<Map<String, Object>> buildBlockedResponse(
            GatewayResponse response) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", response.getStatusCode());
        body.put("error", httpStatusText(response.getStatusCode()));
        body.put("message", response.getReason());
        body.put("blockedBy", response.getBlockedBy());
        body.put("timestamp", Instant.now().toString());

        if (response.getRetryAfterSeconds() != null) {
            body.put("retryAfterSeconds", response.getRetryAfterSeconds());
        }

        var builder = ResponseEntity.status(response.getStatusCode());

        if (response.getRetryAfterSeconds() != null) {
            builder = builder.header("Retry-After",
                    String.valueOf(response.getRetryAfterSeconds()));
        }

        return builder.body(body);
    }

    /**
     * Remove hop-by-hop headers that should not be forwarded
     * from downstream back to the original client.
     */
    private HttpHeaders sanitizeHeaders(HttpHeaders headers) {
        HttpHeaders sanitized = new HttpHeaders();
        headers.forEach((name, values) -> {
            String lower = name.toLowerCase();
            if (!lower.equals("transfer-encoding")
                    && !lower.equals("connection")
                    && !lower.equals("keep-alive")) {
                sanitized.put(name, values);
            }
        });
        return sanitized;
    }

    private Map<String, Object> errorBody(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", httpStatusText(status));
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    private String httpStatusText(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default  -> "Unknown";
        };
    }
}