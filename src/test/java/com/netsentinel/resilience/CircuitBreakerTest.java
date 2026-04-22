package com.netsentinel.resilience;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CircuitBreakerTest {
    @Test
    void opensWhenFailureRateExceedsThreshold() {
        CircuitBreaker breaker = new CircuitBreaker(0.15d, Duration.ofMillis(500), Duration.ofSeconds(30));

        for (int i = 0; i < 25; i++) {
            breaker.recordFailure(Duration.ofMillis(10).toNanos());
        }

        assertEquals(CircuitState.OPEN, breaker.state());
        assertFalse(breaker.allowRequest());
    }

    @Test
    void opensWhenP99LatencyExceedsThreshold() {
        CircuitBreaker breaker = new CircuitBreaker(0.15d, Duration.ofMillis(500), Duration.ofSeconds(30));

        for (int i = 0; i < 21; i++) {
            breaker.recordSuccess(Duration.ofMillis(i == 20 ? 750 : 20).toNanos());
        }

        assertEquals(CircuitState.OPEN, breaker.state());
    }
}
