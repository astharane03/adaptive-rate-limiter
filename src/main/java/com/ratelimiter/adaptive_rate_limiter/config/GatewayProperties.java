package com.ratelimiter.adaptive_rate_limiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds all "gateway.*" entries from application.properties into this class.
 *
 * Because AdaptiveRateLimiterApplication has @ConfigurationPropertiesScan,
 * Spring finds this automatically — no manual registration needed.
 *
 * Any class that needs gateway config just injects GatewayProperties.
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private Downstream downstream = new Downstream();
    private DefaultRules defaultRules = new DefaultRules();
    private ShadowMode shadowMode = new ShadowMode();

    // ── Getters and Setters ───────────────────────────────────
    // (Spring needs setters to bind the values from properties file)

    public Downstream getDownstream() { return downstream; }
    public void setDownstream(Downstream downstream) { this.downstream = downstream; }

    public DefaultRules getDefaultRules() { return defaultRules; }
    public void setDefaultRules(DefaultRules defaultRules) { this.defaultRules = defaultRules; }

    public ShadowMode getShadowMode() { return shadowMode; }
    public void setShadowMode(ShadowMode shadowMode) { this.shadowMode = shadowMode; }

    // ── Nested config classes ─────────────────────────────────

    public static class Downstream {
        private String baseUrl = "http://localhost:9090";
        private int connectTimeoutMs = 2000;
        private int readTimeoutMs = 5000;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int ms) { this.connectTimeoutMs = ms; }

        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int ms) { this.readTimeoutMs = ms; }
    }

    public static class DefaultRules {
        private int requestsPerMinute = 60;
        private int burstCapacity = 10;
        private String algorithm = "TOKEN_BUCKET";

        public int getRequestsPerMinute() { return requestsPerMinute; }
        public void setRequestsPerMinute(int r) { this.requestsPerMinute = r; }

        public int getBurstCapacity() { return burstCapacity; }
        public void setBurstCapacity(int b) { this.burstCapacity = b; }

        public String getAlgorithm() { return algorithm; }
        public void setAlgorithm(String a) { this.algorithm = a; }
    }

    public static class ShadowMode {
        private boolean enabled = false;
        private String logPrefix = "[SHADOW]";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }

        public String getLogPrefix() { return logPrefix; }
        public void setLogPrefix(String p) { this.logPrefix = p; }
    }
}