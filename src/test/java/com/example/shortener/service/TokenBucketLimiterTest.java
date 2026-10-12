package com.example.shortener.service;

import com.example.shortener.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketLimiterTest {

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void allowsBurstThenDeniesWithRetryAfter() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(3, 1.0, 100, clock);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        }
        TokenBucketLimiter.Decision denied = limiter.tryAcquire("a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void clientsAreIsolated() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(1, 0.1, 100, clock);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    void refillsOverTimeButNeverBeyondCapacity() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(3, 1.0, 100, clock);
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a");
        }
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();

        clock.advance(Duration.ofHours(1));
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire("a").allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(3);
    }

    @Test
    void memoryIsBounded_failOpenForNewKeysWhenSaturated_andIdleBucketsAreEvicted() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(1, 1.0, 5, clock);
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("k" + i); // every bucket drained, none idle yet
        }
        assertThat(limiter.tryAcquire("new").allowed()).isTrue();
        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(5);

        clock.advance(Duration.ofSeconds(10)); // all buckets are full again => idle
        limiter.tryAcquire("another");
        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(5);
        assertThat(limiter.tryAcquire("another").allowed()).isFalse(); // tracked normally again
    }

    @Test
    void evictionScanIsThrottled_soADistinctKeyFloodCannotForceAScanPerRequest() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(1, 10.0, 5, clock); // a drained bucket refills in 100 ms
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("k" + i);
        }
        limiter.tryAcquire("first-saturated"); // scans now; nothing is idle yet

        clock.advance(Duration.ofMillis(300)); // every bucket is idle now, but the last scan was <1 s ago
        assertThat(limiter.tryAcquire("x").allowed()).isTrue();
        assertThat(limiter.tryAcquire("x").allowed()).isTrue(); // still untracked => not limited (fail-open), scan skipped
        assertThat(limiter.trackedKeys()).isEqualTo(5);

        clock.advance(Duration.ofSeconds(1)); // interval elapsed: the scan runs and frees the idle buckets
        limiter.tryAcquire("y");
        assertThat(limiter.tryAcquire("y").allowed()).isFalse(); // now tracked and enforced
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new TokenBucketLimiter(0, 1, 10, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketLimiter(1, 0, 10, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketLimiter(1, 1, 0, clock)).isInstanceOf(IllegalArgumentException.class);
    }
}
