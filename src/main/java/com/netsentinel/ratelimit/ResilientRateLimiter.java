package com.netsentinel.ratelimit;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

public final class ResilientRateLimiter implements RateLimiter {
    private final RateLimiter primary;
    private final RateLimiter fallback;
    private final long fallbackWindowNanos;
    private final AtomicLong fallbackUntilNanos = new AtomicLong();

    public ResilientRateLimiter(RateLimiter primary, RateLimiter fallback, Duration fallbackWindow) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.fallbackWindowNanos = Math.max(0L, Objects.requireNonNull(fallbackWindow, "fallbackWindow").toNanos());
    }

    @Override
    public CompletionStage<Boolean> allow(String key) {
        long now = System.nanoTime();
        if (now < fallbackUntilNanos.get()) {
            return fallback.allow(key);
        }

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        primary.allow(key).whenComplete((allowed, failure) -> {
            if (failure == null) {
                result.complete(Boolean.TRUE.equals(allowed));
                return;
            }
            fallbackUntilNanos.set(System.nanoTime() + fallbackWindowNanos);
            fallback.allow(key).whenComplete((fallbackAllowed, fallbackFailure) -> {
                if (fallbackFailure != null) {
                    result.completeExceptionally(fallbackFailure);
                    return;
                }
                result.complete(Boolean.TRUE.equals(fallbackAllowed));
            });
        });
        return result;
    }

    @Override
    public void close() {
        closeQuietly(primary);
        closeQuietly(fallback);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}