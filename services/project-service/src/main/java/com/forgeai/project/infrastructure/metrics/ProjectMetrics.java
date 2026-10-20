package com.forgeai.project.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ProjectMetrics {

    private final MeterRegistry meterRegistry;

    // Traceability metrics
    private final Counter traceabilitySyncCounter;
    private final Counter traceabilitySyncFailureCounter;
    private final Counter traceabilityDegradedCounter;
    private final Counter traceabilityProjectsProcessedCounter;
    private final Counter traceabilityGapsCounter;

    // Outbox metrics
    private final Counter outboxProcessingCounter;
    private final Counter outboxRetryCounter;
    private final Counter outboxDeadLetterCounter;
    private final Timer outboxProcessingTimer;
    private final AtomicInteger pendingOutboxGauge = new AtomicInteger(0);

    // Circuit breaker state gauges: serviceName -> AtomicInteger (0=CLOSED, 1=HALF_OPEN, 2=OPEN)
    private final ConcurrentHashMap<String, AtomicInteger> circuitBreakerStates = new ConcurrentHashMap<>();

    public ProjectMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.traceabilitySyncCounter = Counter.builder("traceability_sync_total")
                .description("Total number of traceability synchronization operations")
                .register(meterRegistry);

        this.traceabilitySyncFailureCounter = Counter.builder("traceability_sync_failure_total")
                .description("Total number of failed traceability sync operations")
                .register(meterRegistry);

        this.traceabilityDegradedCounter = Counter.builder("traceability_degraded_total")
                .description("Total times traceability evidence was degraded due to downstream circuit opening")
                .register(meterRegistry);

        this.traceabilityProjectsProcessedCounter = Counter.builder("traceability_projects_processed")
                .description("Total projects evaluated for traceability")
                .register(meterRegistry);

        this.traceabilityGapsCounter = Counter.builder("traceability_gaps_detected")
                .description("Total traceability gaps discovered")
                .register(meterRegistry);

        this.outboxProcessingCounter = Counter.builder("outbox_processing_total")
                .description("Total outbox events published successfully")
                .register(meterRegistry);

        this.outboxRetryCounter = Counter.builder("outbox_retry_total")
                .description("Total outbox events scheduled for retry")
                .register(meterRegistry);

        this.outboxDeadLetterCounter = Counter.builder("outbox_dead_letter_total")
                .description("Total outbox events transitioned to dead-letter")
                .register(meterRegistry);

        this.outboxProcessingTimer = Timer.builder("outbox_processing_latency")
                .description("Latency of outbox event dispatch in milliseconds")
                .register(meterRegistry);

        Gauge.builder("outbox_pending_events", pendingOutboxGauge, AtomicInteger::get)
                .description("Current pending outbox events count")
                .register(meterRegistry);
    }

    public void recordTraceabilitySync() {
        traceabilitySyncCounter.increment();
        traceabilityProjectsProcessedCounter.increment();
    }

    public void recordTraceabilitySyncFailure() {
        traceabilitySyncFailureCounter.increment();
    }

    public void recordTraceabilityDegraded() {
        traceabilityDegradedCounter.increment();
    }

    public void recordGapsDetected(int count) {
        if (count > 0) {
            traceabilityGapsCounter.increment(count);
        }
    }

    public void recordCircuitBreakerOpen(String serviceName) {
        Counter.builder("circuit_breaker_open_total")
                .tag("service", serviceName)
                .register(meterRegistry)
                .increment();
        updateCircuitBreakerState(serviceName, 2); // 2 = OPEN
    }

    public void recordCircuitBreakerFastFail(String serviceName) {
        Counter.builder("circuit_breaker_fast_fail_total")
                .tag("service", serviceName)
                .register(meterRegistry)
                .increment();
    }

    public void updateCircuitBreakerState(String serviceName, int stateVal) {
        circuitBreakerStates.computeIfAbsent(serviceName, k -> {
            AtomicInteger val = new AtomicInteger(stateVal);
            Gauge.builder("circuit_breaker_state", val, AtomicInteger::get)
                    .tag("service", serviceName)
                    .register(meterRegistry);
            return val;
        }).set(stateVal);
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

    public ConcurrentHashMap<String, AtomicInteger> getCircuitBreakerStates() {
        return circuitBreakerStates;
    }

    public double getTraceabilitySyncCount() {
        return traceabilitySyncCounter.count();
    }

    public double getTraceabilitySyncFailureCount() {
        return traceabilitySyncFailureCounter.count();
    }

    public double getTraceabilityDegradedCount() {
        return traceabilityDegradedCounter.count();
    }

    public double getTraceabilityProjectsProcessed() {
        return traceabilityProjectsProcessedCounter.count();
    }

    public double getTraceabilityGapsDetected() {
        return traceabilityGapsCounter.count();
    }
}
