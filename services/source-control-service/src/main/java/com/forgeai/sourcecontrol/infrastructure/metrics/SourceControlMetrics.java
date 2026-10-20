package com.forgeai.sourcecontrol.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class SourceControlMetrics {

    private final MeterRegistry meterRegistry;

    // Webhook metrics
    private final Counter webhookDuplicateCounter;
    private final Counter syncSuccessCounter;
    private final Counter syncFailureCounter;

    // Outbox metrics
    private final Counter outboxProcessingCounter;
    private final Counter outboxRetryCounter;
    private final Counter outboxDeadLetterCounter;
    private final Timer outboxProcessingTimer;

    // Gauge values
    private final AtomicInteger activeInstallationsGauge = new AtomicInteger(0);
    private final AtomicInteger quarantinedInstallationsGauge = new AtomicInteger(0);
    private final AtomicInteger suspendedInstallationsGauge = new AtomicInteger(0);
    private final AtomicInteger pendingOutboxGauge = new AtomicInteger(0);
    private final AtomicInteger rateLimitRemainingGauge = new AtomicInteger(5000);
    private final AtomicLong rateLimitResetGauge = new AtomicLong(0);

    public SourceControlMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.webhookDuplicateCounter = Counter.builder("github_webhook_duplicate_total")
                .description("Total number of duplicate webhooks ignored")
                .register(meterRegistry);

        this.syncSuccessCounter = Counter.builder("github_sync_success_total")
                .description("Total successful repository synchronization tasks")
                .register(meterRegistry);

        this.syncFailureCounter = Counter.builder("github_sync_failure_total")
                .description("Total failed repository synchronization tasks")
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

        // Gauges
        Gauge.builder("github_installations_active", activeInstallationsGauge, AtomicInteger::get)
                .description("Active GitHub App installations count")
                .register(meterRegistry);

        Gauge.builder("github_installations_quarantined", quarantinedInstallationsGauge, AtomicInteger::get)
                .description("Quarantined GitHub App installations count")
                .register(meterRegistry);

        Gauge.builder("github_installations_suspended", suspendedInstallationsGauge, AtomicInteger::get)
                .description("Suspended GitHub App installations count")
                .register(meterRegistry);

        Gauge.builder("outbox_pending_events", pendingOutboxGauge, AtomicInteger::get)
                .description("Current pending outbox events awaiting processing")
                .register(meterRegistry);

        Gauge.builder("github_rate_limit_remaining", rateLimitRemainingGauge, AtomicInteger::get)
                .description("Current GitHub API rate limit remaining quota")
                .register(meterRegistry);

        Gauge.builder("github_rate_limit_reset", rateLimitResetGauge, AtomicLong::get)
                .description("GitHub API rate limit reset timestamp")
                .register(meterRegistry);
    }

    public void recordGitHubApiRequest(String endpointCategory, String httpMethod, int statusCode, long latencyMs) {
        Counter.builder("github_api_requests_total")
                .tag("endpoint", endpointCategory)
                .tag("method", httpMethod)
                .tag("status", String.valueOf(statusCode))
                .register(meterRegistry)
                .increment();

        Timer.builder("github_api_latency")
                .tag("endpoint", endpointCategory)
                .register(meterRegistry)
                .record(latencyMs, TimeUnit.MILLISECONDS);

        if (statusCode >= 400) {
            Counter.builder("github_api_failures_total")
                    .tag("endpoint", endpointCategory)
                    .tag("method", httpMethod)
                    .tag("status", String.valueOf(statusCode))
                    .register(meterRegistry)
                    .increment();
        }
    }

    public void recordWebhookReceived(String eventType) {
        Counter.builder("github_webhook_received_total")
                .tag("eventType", eventType)
                .register(meterRegistry)
                .increment();
    }

    public void recordWebhookRejected(String reason) {
        Counter.builder("github_webhook_rejected_total")
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }

    public void recordWebhookDuplicate() {
        webhookDuplicateCounter.increment();
    }

    public void recordSyncSuccess() {
        syncSuccessCounter.increment();
    }

    public void recordSyncFailure() {
        syncFailureCounter.increment();
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

    public void updateInstallationCounts(int active, int quarantined, int suspended) {
        activeInstallationsGauge.set(active);
        quarantinedInstallationsGauge.set(quarantined);
        suspendedInstallationsGauge.set(suspended);
    }

    public void updatePendingOutboxCount(int count) {
        pendingOutboxGauge.set(count);
    }

    public void updateRateLimit(int remaining, long resetEpochSeconds) {
        rateLimitRemainingGauge.set(remaining);
        rateLimitResetGauge.set(resetEpochSeconds);
    }

    public int getRateLimitRemaining() {
        return rateLimitRemainingGauge.get();
    }

    public long getRateLimitReset() {
        return rateLimitResetGauge.get();
    }

    public int getActiveInstallations() {
        return activeInstallationsGauge.get();
    }

    public int getQuarantinedInstallations() {
        return quarantinedInstallationsGauge.get();
    }

    public int getSuspendedInstallations() {
        return suspendedInstallationsGauge.get();
    }
}
