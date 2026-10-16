package com.forgeai.project.infrastructure.client;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * SimpleCircuitBreaker
 * <p>
 * A lightweight, synchronous circuit breaker compatible with the existing java.net.http.HttpClient
 * architecture. Prevents cascading failures when a downstream service is unavailable.
 * <p>
 * States:
 * <ul>
 *   <li>CLOSED: Normal operation. Failures are counted.</li>
 *   <li>OPEN: After {@code failureThreshold} consecutive failures, the circuit opens and all
 *       calls are rejected immediately (fast-fail) with an empty fallback, preventing unbounded
 *       hammering of an unavailable service.</li>
 *   <li>HALF_OPEN: After {@code resetTimeout} elapses, the next call is tried as a probe.
 *       Success closes the circuit; failure re-opens it and resets the timeout.</li>
 * </ul>
 * <p>
 * This is not a general-purpose library — it is intentionally minimal and maintainable
 * within a single JAR with no additional infrastructure dependencies.
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

    public SimpleCircuitBreaker(String serviceName, int failureThreshold, Duration resetTimeout) {
        this.serviceName = serviceName;
        this.failureThreshold = failureThreshold;
        this.resetTimeout = resetTimeout;
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    /**
     * Execute a downstream call through the circuit breaker.
     * If the circuit is OPEN, the fallback is returned immediately without invoking the supplier.
     *
     * @param <T>      return type
     * @param call     the actual downstream call (may throw checked exceptions)
     * @param fallback the value to return when the circuit is open or a failure occurs
     * @return the result of the call, or the fallback
     */
    public <T> T execute(CheckedSupplier<T> call, T fallback) {
        State current = currentState();

        if (current == State.OPEN) {
            log.warn("[CircuitBreaker:{}] Circuit OPEN — fast-failing request to protect downstream", serviceName);
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

    /**
     * Returns true if the circuit is currently open (downstream unavailable).
     * Callers can use this to distinguish "no data available" from "genuinely empty results".
     */
    public boolean isOpen() {
        return currentState() == State.OPEN;
    }

    public State currentState() {
        State s = state.get();
        if (s == State.OPEN && openedAt != null) {
            if (Duration.between(openedAt, Instant.now()).compareTo(resetTimeout) >= 0) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    log.info("[CircuitBreaker:{}] Circuit transitioning to HALF_OPEN (probe next request)", serviceName);
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
            }
        }
    }

    public String getServiceName() {
        return serviceName;
    }
}
