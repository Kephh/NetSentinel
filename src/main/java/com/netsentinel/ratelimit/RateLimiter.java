package com.netsentinel.ratelimit;

import java.util.concurrent.CompletionStage;

public interface RateLimiter extends AutoCloseable {
    CompletionStage<Boolean> allow(String key);

    @Override
    default void close() {
    }
}
