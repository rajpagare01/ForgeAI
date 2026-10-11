package com.forgeai.sourcecontrol.presentation.controller;

import com.forgeai.sourcecontrol.application.service.WebhookIngestionResult;
import com.forgeai.sourcecontrol.application.service.WebhookIngestionService;
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

    @PostMapping("/github")
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestHeader("X-GitHub-Delivery") String deliveryId,
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signatureHeader,
            @RequestBody byte[] payloadBytes) {

        log.info("Received GitHub webhook: deliveryId={}, eventType={}", deliveryId, eventType);

        WebhookIngestionResult result = webhookIngestionService.ingestWebhook(
                deliveryId,
                eventType,
                signatureHeader,
                payloadBytes
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "status", result.getStatus(),
                "deliveryId", result.getDeliveryId(),
                "idempotentHit", result.isIdempotentHit()
        ));
    }
}
