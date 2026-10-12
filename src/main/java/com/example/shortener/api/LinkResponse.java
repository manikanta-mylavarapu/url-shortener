package com.example.shortener.api;

import com.example.shortener.domain.ShortLink;

import java.time.Instant;

public record LinkResponse(
        String code,
        String shortUrl,
        String originalUrl,
        Instant createdAt,
        Instant expiresAt,
        ShortLink.Status status,
        long totalClicks) {

    public static LinkResponse from(ShortLink link, String baseUrl, Instant now) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return new LinkResponse(
                link.getCode(),
                base + "/" + link.getCode(),
                link.getOriginalUrl(),
                link.getCreatedAt(),
                link.getExpiresAt(),
                link.statusAt(now),
                link.getClickCount());
    }
}
