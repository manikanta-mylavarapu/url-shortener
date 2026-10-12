package com.example.shortener.service;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-key token-bucket rate limiter (single node, in memory).
 *
 * <p>Design notes:
 * <ul>
 *   <li>Time comes from an injected {@link Clock}, so behaviour is unit-testable without sleeping.</li>
 *   <li>Memory is bounded: when more than {@code maxTrackedKeys} keys are tracked, buckets that
 *       have fully refilled (idle clients) are evicted. If the map is still full, <em>new</em> keys
 *       are let through untracked (fail-open) so a flood of distinct IPs cannot lock legitimate
 *       users out; the trade-off is that such a flood is not rate limited.</li>
 *   <li>Single-node only. A multi-instance deployment needs a shared store (e.g. Redis) or
 *       enforcement at the gateway.</li>
 * </ul>
 */
public final class TokenBucketLimiter {

    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);
    }

    private static final class Bucket {
        double tokens;
        long lastRefillMillis;

        Bucket(double tokens, long lastRefillMillis) {
            this.tokens = tokens;
            this.lastRefillMillis = lastRefillMillis;
        }
    }

    private final int capacity;
    private final double refillPerSecond;
    private final int maxTrackedKeys;
    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    /** Eviction is a full scan; throttled so a flood of distinct keys cannot turn every request into an O(n) scan. */
    private static final long EVICTION_INTERVAL_MILLIS = 1_000;
    private final AtomicLong lastEvictionMillis = new AtomicLong(Long.MIN_VALUE / 2);

    public TokenBucketLimiter(int capacity, double refillPerSecond, int maxTrackedKeys, Clock clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        if (refillPerSecond <= 0) {
            throw new IllegalArgumentException("refillPerSecond must be > 0");
        }
        if (maxTrackedKeys < 1) {
            throw new IllegalArgumentException("maxTrackedKeys must be >= 1");
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.maxTrackedKeys = maxTrackedKeys;
        this.clock = clock;
    }

    public Decision tryAcquire(String key) {
        long now = clock.millis();
        if (buckets.size() >= maxTrackedKeys) {
            evictIdleAtMostOncePerInterval(now);
            if (buckets.size() >= maxTrackedKeys && !buckets.containsKey(key)) {
                return Decision.ALLOWED; // fail-open for new keys when the table is saturated
            }
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (bucket) {
            refill(bucket, now);
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return Decision.ALLOWED;
            }
            long waitSeconds = (long) Math.ceil((1.0 - bucket.tokens) / refillPerSecond);
            return new Decision(false, Math.max(1, waitSeconds));
        }
    }

    int trackedKeys() {
        return buckets.size();
    }

    private void refill(Bucket bucket, long now) {
        long elapsed = Math.max(0, now - bucket.lastRefillMillis);
        bucket.tokens = Math.min(capacity, bucket.tokens + (elapsed / 1000.0) * refillPerSecond);
        bucket.lastRefillMillis = now;
    }

    private void evictIdleAtMostOncePerInterval(long now) {
        long last = lastEvictionMillis.get();
        if (now - last >= EVICTION_INTERVAL_MILLIS && lastEvictionMillis.compareAndSet(last, now)) {
            evictIdle(now);
        }
    }

    private void evictIdle(long now) {
        buckets.entrySet().removeIf(entry -> {
            Bucket bucket = entry.getValue();
            synchronized (bucket) {
                refill(bucket, now);
                return bucket.tokens >= capacity;
            }
        });
    }
}
