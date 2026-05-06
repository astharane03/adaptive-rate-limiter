package com.ratelimiter.adaptive_rate_limiter.exception;

public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;
    private final String clientKey;

    public RateLimitExceededException(String clientKey, long retryAfterSeconds) {
        super("Rate limit exceeded for client: " + clientKey);
        this.clientKey = clientKey;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public String getClientKey() {
        return clientKey;
    }
}