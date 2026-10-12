package com.example.shortener.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** The limiter must not be bypassable by how the path is spelled. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitFilterTest {

    @Mock HttpServletRequest request;

    private final RateLimitFilter filter = new RateLimitFilter(
            null, null, new AppProperties("http://localhost:8080", false, 7, 3, "salt",
            new AppProperties.RateLimit(true, 30, 0.5, false, 10_000)));

    @Test
    void everyStateChangingMethodIsLimited_whateverTheSpellingOfThePath() {
        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            for (String uri : new String[]{"/api/v1/links", "/api;x=1/v1/links", "//api/v1/links", "/API/v1/links",
                    "/api/v1/links/", "/anything-else"}) {
                when(request.getMethod()).thenReturn(method);
                when(request.getRequestURI()).thenReturn(uri);
                assertThat(filter.shouldNotFilter(request)).as(method + " " + uri).isFalse();
            }
        }
    }

    @Test
    void readsAreNotLimited() {
        for (String method : new String[]{"GET", "HEAD", "OPTIONS"}) {
            when(request.getMethod()).thenReturn(method);
            when(request.getRequestURI()).thenReturn("/api/v1/links");
            assertThat(filter.shouldNotFilter(request)).as(method).isTrue();
        }
    }
}
