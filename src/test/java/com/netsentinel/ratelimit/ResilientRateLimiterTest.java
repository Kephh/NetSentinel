package com.netsentinel.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResilientRateLimiterTest {
    @Test
    void usesPrimaryLimiterWhenPrimaryIsHealthy() {
        RecordingRateLimiter primary = new RecordingRateLimiter(key -> CompletableFuture.completedFuture(true));
        RecordingRateLimiter fallback = new RecordingRateLimiter(key -> CompletableFuture.completedFuture(false));
        try (ResilientRateLimiter limiter = new ResilientRateLimiter(primary, fallback, Duration.ofSeconds(30))) {
            boolean allowed = limiter.allow("client-a").toCompletableFuture().join();

            assertTrue(allowed);
            assertEquals(1, primary.invocations());
            assertEquals(0, fallback.invocations());
        }
    }

    @Test
    void fallsBackAndStaysOnFallbackForConfiguredWindow() {
        RecordingRateLimiter primary = new RecordingRateLimiter(key -> failedFuture(new IllegalStateException("redis down")));
        AtomicInteger fallbackCalls = new AtomicInteger();
        RecordingRateLimiter fallback = new RecordingRateLimiter(key ->
                CompletableFuture.completedFuture(fallbackCalls.incrementAndGet() == 1)
        );
        try (ResilientRateLimiter limiter = new ResilientRateLimiter(primary, fallback, Duration.ofSeconds(30))) {
            boolean first = limiter.allow("client-a").toCompletableFuture().join();
            boolean second = limiter.allow("client-a").toCompletableFuture().join();

            assertTrue(first);
            assertFalse(second);
            assertEquals(1, primary.invocations());
            assertEquals(2, fallback.invocations());
        }
    }

    @Test
    void closesPrimaryAndFallback() {
        RecordingRateLimiter primary = new RecordingRateLimiter(key -> CompletableFuture.completedFuture(true));
        RecordingRateLimiter fallback = new RecordingRateLimiter(key -> CompletableFuture.completedFuture(true));
        ResilientRateLimiter limiter = new ResilientRateLimiter(primary, fallback, Duration.ofSeconds(30));

        limiter.close();

        assertEquals(1, primary.closes());
        assertEquals(1, fallback.closes());
    }

    private static CompletionStage<Boolean> failedFuture(Throwable error) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        result.completeExceptionally(error);
        return result;
    }

    private static final class RecordingRateLimiter implements RateLimiter {
        private final Function<String, CompletionStage<Boolean>> behavior;
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        private RecordingRateLimiter(Function<String, CompletionStage<Boolean>> behavior) {
            this.behavior = behavior;
        }

        @Override
        public CompletionStage<Boolean> allow(String key) {
            invocations.incrementAndGet();
            return behavior.apply(key);
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }

        private int invocations() {
            return invocations.get();
        }

        private int closes() {
            return closes.get();
        }
    }
}
