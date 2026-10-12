package com.example.shortener.repo;

/** Clicks attributed to one referrer host, or "direct" (query projection). */
public record ReferrerCount(String referrer, Long clicks) {
}
