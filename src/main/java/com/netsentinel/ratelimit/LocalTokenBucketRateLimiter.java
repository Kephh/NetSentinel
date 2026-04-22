package com.netsentinel.ratelimit;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class LocalTokenBucketRateLimiter implements RateLimiter {
    private final long capacity;
    private final double refillPerNanos;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public LocalTokenBucketRateLimiter(long capacity, long refillPerSecond) {
        this.capacity = Math.max(1, capacity);
        this.refillPerNanos = Math.max(1, refillPerSecond) / 1_000_000_000.0d;
    }

    @Override
    public CompletionStage<Boolean> allow(String key) {
        String bucketKey = Objects.requireNonNullElse(key, "anonymous");
        Bucket bucket = buckets.computeIfAbsent(bucketKey, ignored -> new Bucket(capacity, System.nanoTime()));
        return CompletableFuture.completedFuture(bucket.tryConsume(capacity, refillPerNanos));
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        private Bucket(double tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
        }

        private synchronized boolean tryConsume(long capacity, double refillPerNanos) {
            long now = System.nanoTime();
            long elapsed = Math.max(0L, now - lastRefillNanos);
            tokens = Math.min(capacity, tokens + (elapsed * refillPerNanos));
            lastRefillNanos = now;
            if (tokens >= 1.0d) {
                tokens -= 1.0d;
                return true;
            }
            return false;
        }
    }
}
