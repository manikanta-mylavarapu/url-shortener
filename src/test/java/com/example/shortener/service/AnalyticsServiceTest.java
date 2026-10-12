package com.example.shortener.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsServiceTest {

    @Test
    void referrerKeepsOnlyTheHost_neverPathOrQuery() {
        assertThat(AnalyticsService.referrerHost("https://News.Example.org/story?token=secret#x")).isEqualTo("news.example.org");
    }

    @Test
    void missingOrUnparsableReferrerIsBucketed() {
        assertThat(AnalyticsService.referrerHost(null)).isEqualTo("direct");
        assertThat(AnalyticsService.referrerHost("  ")).isEqualTo("direct");
        assertThat(AnalyticsService.referrerHost("not a uri at all")).isEqualTo("unknown");
        assertThat(AnalyticsService.referrerHost("android-app://com.example")).isEqualTo("com.example");
        assertThat(AnalyticsService.referrerHost("mailto:someone@example.org")).isEqualTo("unknown");
    }
}
