package com.netsentinel.ratelimit;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class NoopRateLimiter implements RateLimiter {
    @Override
    public CompletionStage<Boolean> allow(String key) {
        return CompletableFuture.completedFuture(true);
    }
}
