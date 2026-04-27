package com.netsentinel.resilience;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class CircuitBreaker {
    public interface StateChangeListener {
        void onStateChange(CircuitState newState);
    }
    private static final int WINDOW_SIZE = 128;
    private static final int MINIMUM_SAMPLES = 20;

    private final double failureThreshold;
    private final long p99LatencyThresholdNanos;
    private final long openCooldownNanos;
    private final AtomicReference<CircuitState> state = new AtomicReference<>(CircuitState.CLOSED);
    private final boolean[] failures = new boolean[WINDOW_SIZE];
    private final long[] latencies = new long[WINDOW_SIZE];

    private int cursor;
    private int count;
    private long openedAtNanos;
    private StateChangeListener listener;

    public CircuitBreaker(double failureThreshold, Duration p99LatencyThreshold, Duration openCooldown) {
        this.failureThreshold = failureThreshold;
        this.p99LatencyThresholdNanos = p99LatencyThreshold.toNanos();
        this.openCooldownNanos = openCooldown.toNanos();
    }

    public void setStateChangeListener(StateChangeListener listener) {
        this.listener = listener;
    }

    public boolean allowRequest() {
        CircuitState current = state.get();
        if (current == CircuitState.CLOSED) {
            return true;
        }
        if (current == CircuitState.HALF_OPEN) {
            return true;
        }
        long elapsed = System.nanoTime() - openedAtNanos;
        if (elapsed >= openCooldownNanos && state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
            return true;
        }
        return false;
    }

    public synchronized void recordSuccess(long latencyNanos) {
        record(false, latencyNanos);
        if (state.get() == CircuitState.HALF_OPEN) {
            state.set(CircuitState.CLOSED);
            notifyListener(CircuitState.CLOSED);
        }
    }

    public synchronized void recordFailure(long latencyNanos) {
        record(true, latencyNanos);
        trip();
    }

    public CircuitState state() {
        return state.get();
    }

    public synchronized double failureRate() {
        if (count == 0) {
            return 0.0d;
        }
        int failuresSeen = 0;
        for (int i = 0; i < count; i++) {
            if (failures[i]) {
                failuresSeen++;
            }
        }
        return (double) failuresSeen / count;
    }

    public synchronized long p99LatencyNanos() {
        if (count == 0) {
            return 0L;
        }
        long[] copy = Arrays.copyOf(latencies, count);
        Arrays.sort(copy);
        int index = Math.min(copy.length - 1, (int) Math.ceil(copy.length * 0.99d) - 1);
        return copy[index];
    }

    private void record(boolean failed, long latencyNanos) {
        failures[cursor] = failed;
        latencies[cursor] = Math.max(0L, latencyNanos);
        cursor = (cursor + 1) % WINDOW_SIZE;
        count = Math.min(WINDOW_SIZE, count + 1);
        if (failed && state.get() == CircuitState.HALF_OPEN) {
            trip();
            return;
        }
        if (count >= MINIMUM_SAMPLES && (failureRate() > failureThreshold || p99LatencyNanos() > p99LatencyThresholdNanos)) {
            trip();
        }
    }

    private void trip() {
        openedAtNanos = System.nanoTime();
        if (state.getAndSet(CircuitState.OPEN) != CircuitState.OPEN) {
            notifyListener(CircuitState.OPEN);
        }
    }

    private void notifyListener(CircuitState newState) {
        if (listener != null) {
            listener.onStateChange(newState);
        }
    }
}
