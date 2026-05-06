package com.ratelimiter.adaptive_rate_limiter.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

@Getter
@Builder
@ToString
public class ClientIdentity {

    private final String apiKey;
    private final String clientName;
    private final ClientTier tier;
    private final String ipAddress;

    /**
     * This is the Redis key prefix for all counters belonging to this client.
     * "client:abc123" gets its own bucket. "ip:192.168.1.1" gets its own bucket.
     * Two different clients NEVER share the same counter.
     */
    public String getRateLimitKey() {
        if (apiKey != null && !apiKey.isBlank()) {
            return "client:" + apiKey;
        }
        return "ip:" + ipAddress;
    }

    public boolean isAuthenticated() {
        return apiKey != null && !apiKey.isBlank();
    }

    public static ClientIdentity anonymous(String ipAddress) {
        return ClientIdentity.builder()
                .ipAddress(ipAddress)
                .clientName("anonymous:" + ipAddress)
                .tier(ClientTier.FREE)
                .build();
    }

    public enum ClientTier {
        FREE(60),
        STANDARD(500),
        PREMIUM(5000);

        private final int defaultRequestsPerMinute;

        ClientTier(int rpm) {
            this.defaultRequestsPerMinute = rpm;
        }

        public int getDefaultRequestsPerMinute() {
            return defaultRequestsPerMinute;
        }
    }
}