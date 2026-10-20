package com.forgeai.identity.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class IdentityMetrics {

    private final MeterRegistry meterRegistry;

    private final Counter outboxProcessingCounter;
    private final Counter outboxRetryCounter;
    private final Counter outboxDeadLetterCounter;
    private final Timer outboxProcessingTimer;
    private final AtomicInteger pendingOutboxGauge = new AtomicInteger(0);

    private final Counter loginSuccessCounter;
    private final Counter loginFailureCounter;
    private final Counter authDeniedCounter;

    public IdentityMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.outboxProcessingCounter = Counter.builder("outbox_processing_total")
                .description("Total outbox events published successfully")
                .register(meterRegistry);

        this.outboxRetryCounter = Counter.builder("outbox_retry_total")
                .description("Total outbox events scheduled for retry")
                .register(meterRegistry);

        this.outboxDeadLetterCounter = Counter.builder("outbox_dead_letter_total")
                .description("Total outbox events moved to dead-letter state")
                .register(meterRegistry);

        this.outboxProcessingTimer = Timer.builder("outbox_processing_latency")
                .description("Latency of outbox event dispatch in milliseconds")
                .register(meterRegistry);

        Gauge.builder("outbox_pending_events", pendingOutboxGauge, AtomicInteger::get)
                .description("Current pending outbox events count")
                .register(meterRegistry);

        this.loginSuccessCounter = Counter.builder("security_login_success_total")
                .description("Total successful user authentications")
                .register(meterRegistry);

        this.loginFailureCounter = Counter.builder("security_login_failure_total")
                .description("Total failed user authentications")
                .register(meterRegistry);

        this.authDeniedCounter = Counter.builder("security_authorization_denied_total")
                .description("Total authorization denied events")
                .register(meterRegistry);
    }

    public void recordOutboxSuccess(long latencyMs) {
        outboxProcessingCounter.increment();
        outboxProcessingTimer.record(latencyMs, TimeUnit.MILLISECONDS);
    }

    public void recordOutboxRetry() {
        outboxRetryCounter.increment();
    }

    public void recordOutboxDeadLetter() {
        outboxDeadLetterCounter.increment();
    }

    public void updatePendingOutboxCount(int count) {
        pendingOutboxGauge.set(count);
    }

    public void recordLoginSuccess() {
        loginSuccessCounter.increment();
    }

    public void recordLoginFailure() {
        loginFailureCounter.increment();
    }

    public void recordAuthDenied() {
        authDeniedCounter.increment();
    }
}
