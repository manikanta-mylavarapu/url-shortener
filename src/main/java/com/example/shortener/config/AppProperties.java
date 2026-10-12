package com.example.shortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** All tunables in one typed, validated-at-startup place. Override via application.yml or env vars. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        /** Public base used to build the returned shortUrl. */
        @DefaultValue("http://localhost:8080") String baseUrl,
        /** SSRF guard on submitted URLs. Disable only in tests or fully isolated environments. */
        @DefaultValue("true") boolean blockPrivateNetworks,
        @DefaultValue("7") int codeLength,
        /** Bounded retry when a generated code collides with an existing one. */
        @DefaultValue("5") int maxCodeAttempts,
        /** Salt for visitor hashing. MUST be overridden in production. */
        @DefaultValue("change-me-in-production") String visitorSalt,
        @DefaultValue RateLimit rateLimit) {

    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            /** Burst size per client. */
            @DefaultValue("30") int capacity,
            /** Sustained rate per client (tokens per second). */
            @DefaultValue("0.5") double refillPerSecond,
            /** Trust X-Forwarded-For. Only enable behind a proxy that overwrites the header. */
            @DefaultValue("false") boolean trustForwardedFor,
            @DefaultValue("10000") int maxTrackedClients) {
    }
}
