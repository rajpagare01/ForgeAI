package com.forgeai.project.resilience;

import com.forgeai.project.infrastructure.client.SimpleCircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class CircuitBreakerResilienceTest {

    @Test
    @DisplayName("Circuit breaker starts in CLOSED state and executes calls normally")
    void testInitialClosedState() {
        SimpleCircuitBreaker cb = new SimpleCircuitBreaker("test-service", 3, Duration.ofSeconds(10));
        assertThat(cb.currentState()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
        assertThat(cb.isOpen()).isFalse();

        String result = cb.execute(() -> "success", "fallback");
        assertThat(result).isEqualTo("success");
        assertThat(cb.currentState()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Circuit breaker transitions to OPEN after consecutive failures reach threshold")
    void testTransitionToOpenOnThreshold() {
        AtomicBoolean openTriggered = new AtomicBoolean(false);
        SimpleCircuitBreaker cb = new SimpleCircuitBreaker("test-service", 2, Duration.ofSeconds(10));
        cb.setListeners(service -> openTriggered.set(true), null, null);

        // Failure 1
        String res1 = cb.execute(() -> { throw new RuntimeException("Downstream timeout"); }, "fallback");
        assertThat(res1).isEqualTo("fallback");
        assertThat(cb.currentState()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);

        // Failure 2 (reaches threshold 2)
        String res2 = cb.execute(() -> { throw new RuntimeException("Downstream connection refused"); }, "fallback");
        assertThat(res2).isEqualTo("fallback");
        assertThat(cb.currentState()).isEqualTo(SimpleCircuitBreaker.State.OPEN);
        assertThat(cb.isOpen()).isTrue();
        assertThat(openTriggered.get()).isTrue();
    }

    @Test
    @DisplayName("Circuit breaker fast-fails immediately when OPEN without invoking supplier")
    void testFastFailWhenOpen() {
        AtomicInteger fastFailCount = new AtomicInteger(0);
        AtomicInteger invocationCount = new AtomicInteger(0);

        SimpleCircuitBreaker cb = new SimpleCircuitBreaker("test-service", 1, Duration.ofMinutes(5));
        cb.setListeners(null, service -> fastFailCount.incrementAndGet(), null);

        // Trip the breaker
        cb.execute(() -> { throw new RuntimeException("Network down"); }, "fallback");
        assertThat(cb.isOpen()).isTrue();

        // Attempt another call while OPEN
        String res = cb.execute(() -> {
            invocationCount.incrementAndGet();
            return "unexpected";
        }, "fallback-fast");

        assertThat(res).isEqualTo("fallback-fast");
        assertThat(invocationCount.get()).isEqualTo(0); // Supplier was NOT called
        assertThat(fastFailCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Circuit breaker transitions to HALF_OPEN after timeout and recovers to CLOSED on success")
    void testHalfOpenRecovery() throws InterruptedException {
        SimpleCircuitBreaker cb = new SimpleCircuitBreaker("test-service", 1, Duration.ofMillis(50));

        // Trip breaker
        cb.execute(() -> { throw new RuntimeException("Temporary glitch"); }, "fallback");
        assertThat(cb.isOpen()).isTrue();

        // Wait past reset timeout
        Thread.sleep(80);

        // State advances to HALF_OPEN
        assertThat(cb.currentState()).isEqualTo(SimpleCircuitBreaker.State.HALF_OPEN);

        // Successful trial call closes the breaker
        String res = cb.execute(() -> "recovered", "fallback");
        assertThat(res).isEqualTo("recovered");
        assertThat(cb.currentState()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
        assertThat(cb.isOpen()).isFalse();
    }
}
