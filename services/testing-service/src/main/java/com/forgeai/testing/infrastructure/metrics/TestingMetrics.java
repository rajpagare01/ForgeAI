package com.forgeai.testing.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class TestingMetrics {

    private final MeterRegistry meterRegistry;

    private final Counter workflowRunsReceivedCounter;
    private final Counter workflowRunsCompletedCounter;
    private final Counter workflowRunsFailedCounter;
    private final Counter workflowRunsRejectedStaleCounter;

    private final Counter outboxProcessingCounter;
    private final Counter outboxRetryCounter;
    private final Counter outboxDeadLetterCounter;
    private final Timer outboxProcessingTimer;
    private final AtomicInteger pendingOutboxGauge = new AtomicInteger(0);

    public TestingMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.workflowRunsReceivedCounter = Counter.builder("workflow_runs_received_total")
                .description("Total number of workflow runs received from GitHub")
                .register(meterRegistry);

        this.workflowRunsCompletedCounter = Counter.builder("workflow_runs_completed_total")
                .description("Total number of workflow runs successfully completed")
                .register(meterRegistry);

        this.workflowRunsFailedCounter = Counter.builder("workflow_runs_failed_total")
                .description("Total number of workflow runs that completed with failure")
                .register(meterRegistry);

        this.workflowRunsRejectedStaleCounter = Counter.builder("workflow_runs_rejected_stale_total")
                .description("Total number of out-of-order stale workflow runs rejected")
                .register(meterRegistry);

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
    }

    public void recordWorkflowRunReceived() {
        workflowRunsReceivedCounter.increment();
    }

    public void recordWorkflowRunCompleted() {
        workflowRunsCompletedCounter.increment();
    }

    public void recordWorkflowRunFailed() {
        workflowRunsFailedCounter.increment();
    }

    public void recordWorkflowRunRejectedStale() {
        workflowRunsRejectedStaleCounter.increment();
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
}
