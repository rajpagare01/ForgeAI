package com.forgeai.project.infrastructure.client;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * SimpleCircuitBreaker
 * <p>
 * A lightweight, synchronous circuit breaker compatible with java.net.http.HttpClient.
 * Prevents cascading failures when downstream services are unavailable.
 */
@Slf4j
public class SimpleCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String serviceName;
    private final int failureThreshold;
    private final Duration resetTimeout;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private volatile Instant openedAt = null;

    private Consumer<String> onOpenListener;
    private Consumer<String> onFastFailListener;
    private Consumer<State> onStateChangeListener;

    public SimpleCircuitBreaker(String serviceName, int failureThreshold, Duration resetTimeout) {
        this.serviceName = serviceName;
        this.failureThreshold = failureThreshold;
        this.resetTimeout = resetTimeout;
    }

    public void setListeners(Consumer<String> onOpen, Consumer<String> onFastFail, Consumer<State> onStateChange) {
        this.onOpenListener = onOpen;
        this.onFastFailListener = onFastFail;
        this.onStateChangeListener = onStateChange;
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    public <T> T execute(CheckedSupplier<T> call, T fallback) {
        State current = currentState();

        if (current == State.OPEN) {
            log.warn("[CircuitBreaker:{}] Circuit OPEN — fast-failing request to protect downstream", serviceName);
            if (onFastFailListener != null) {
                try { onFastFailListener.accept(serviceName); } catch (Exception ignored) {}
            }
            return fallback;
        }

        try {
            T result = call.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure(e);
            return fallback;
        }
    }

    public boolean isOpen() {
        return currentState() == State.OPEN;
    }

    public State currentState() {
        State s = state.get();
        if (s == State.OPEN && openedAt != null) {
            if (Duration.between(openedAt, Instant.now()).compareTo(resetTimeout) >= 0) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    log.info("[CircuitBreaker:{}] Circuit transitioning to HALF_OPEN (probe next request)", serviceName);
                    if (onStateChangeListener != null) {
                        try { onStateChangeListener.accept(State.HALF_OPEN); } catch (Exception ignored) {}
                    }
                }
                return state.get();
            }
        }
        return s;
    }

    private void onSuccess() {
        if (consecutiveFailures.get() > 0 || state.get() != State.CLOSED) {
            log.info("[CircuitBreaker:{}] Probe succeeded — closing circuit", serviceName);
            consecutiveFailures.set(0);
            state.set(State.CLOSED);
            openedAt = null;
            if (onStateChangeListener != null) {
                try { onStateChangeListener.accept(State.CLOSED); } catch (Exception ignored) {}
            }
        }
    }

    private void onFailure(Exception e) {
        int failures = consecutiveFailures.incrementAndGet();
        log.warn("[CircuitBreaker:{}] Failure {} of {} threshold: {}", serviceName, failures, failureThreshold, e.getMessage());

        if (failures >= failureThreshold) {
            if (state.compareAndSet(State.CLOSED, State.OPEN) || state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openedAt = Instant.now();
                log.error("[CircuitBreaker:{}] Circuit OPENED after {} consecutive failures. Will retry in {}",
                        serviceName, failures, resetTimeout);
                if (onOpenListener != null) {
                    try { onOpenListener.accept(serviceName); } catch (Exception ignored) {}
                }
                if (onStateChangeListener != null) {
                    try { onStateChangeListener.accept(State.OPEN); } catch (Exception ignored) {}
                }
            }
        }
    }

    public String getServiceName() {
        return serviceName;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Duration getResetTimeout() {
        return resetTimeout;
    }
}
