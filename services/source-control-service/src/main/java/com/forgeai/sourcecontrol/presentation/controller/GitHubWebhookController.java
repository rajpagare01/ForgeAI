package com.forgeai.sourcecontrol.presentation.controller;

import com.forgeai.sourcecontrol.application.service.WebhookIngestionResult;
import com.forgeai.sourcecontrol.application.service.WebhookIngestionService;
import com.forgeai.sourcecontrol.infrastructure.logging.StructuredLogger;
import com.forgeai.sourcecontrol.infrastructure.metrics.SourceControlMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class GitHubWebhookController {

    private final WebhookIngestionService webhookIngestionService;
    private final SourceControlMetrics metrics;
    private final StructuredLogger structuredLogger;

    @PostMapping("/github")
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestHeader("X-GitHub-Delivery") String deliveryId,
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signatureHeader,
            @RequestBody byte[] payloadBytes) {

        long start = System.currentTimeMillis();
        metrics.recordWebhookReceived(eventType);
        structuredLogger.logEvent("INFO", "github.webhook.received", "handleWebhook", "RECEIVED",
                null, null, "GitHub webhook received: deliveryId=" + deliveryId + ", event=" + eventType,
                Map.of("deliveryId", deliveryId, "eventType", eventType));

        try {
            WebhookIngestionResult result = webhookIngestionService.ingestWebhook(
                    deliveryId,
                    eventType,
                    signatureHeader,
                    payloadBytes
            );

            long latency = System.currentTimeMillis() - start;
            if (result.isIdempotentHit()) {
                metrics.recordWebhookDuplicate();
                structuredLogger.logEvent("INFO", "github.webhook.accepted", "handleWebhook", "IDEMPOTENT_IGNORED",
                        latency, null, "Webhook duplicate ignored for delivery " + deliveryId,
                        Map.of("deliveryId", deliveryId, "eventType", eventType, "idempotentHit", true));
            } else {
                structuredLogger.logEvent("INFO", "github.webhook.accepted", "handleWebhook", "SUCCESS",
                        latency, null, "Webhook accepted for delivery " + deliveryId,
                        Map.of("deliveryId", deliveryId, "eventType", eventType, "idempotentHit", false));
            }

            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "status", result.getStatus(),
                    "deliveryId", result.getDeliveryId(),
                    "idempotentHit", result.isIdempotentHit()
            ));
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            Throwable root = e;
            boolean isConstraintViolation = false;
            while (root != null) {
                if (root instanceof org.springframework.dao.DataIntegrityViolationException
                        || root.getClass().getName().contains("ConstraintViolation")
                        || root.getClass().getName().contains("DataIntegrity")
                        || root.getClass().getName().contains("PSQLException")) {
                    isConstraintViolation = true;
                    break;
                }
                root = root.getCause();
            }

            if (isConstraintViolation) {
                metrics.recordWebhookDuplicate();
                structuredLogger.logEvent("INFO", "github.webhook.accepted", "handleWebhook", "IDEMPOTENT_IGNORED",
                        latency, null, "Concurrent duplicate delivery detected for " + deliveryId,
                        Map.of("deliveryId", deliveryId, "eventType", eventType, "idempotentHit", true));
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                        "status", "IDEMPOTENT_IGNORED",
                        "deliveryId", deliveryId,
                        "idempotentHit", true
                ));
            }

            String reason = e.getClass().getSimpleName();
            metrics.recordWebhookRejected(reason);
            structuredLogger.logEvent("ERROR", "github.webhook.rejected", "handleWebhook", "REJECTED",
                    latency, reason, "Webhook rejected: " + e.getMessage(),
                    Map.of("deliveryId", deliveryId, "eventType", eventType, "reason", reason));

            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
        }
    }
}
