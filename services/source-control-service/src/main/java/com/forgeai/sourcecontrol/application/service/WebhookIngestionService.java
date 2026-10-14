package com.forgeai.sourcecontrol.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.sourcecontrol.application.handler.InstallationEventHandler;
import com.forgeai.sourcecontrol.application.handler.PullRequestEventHandler;
import com.forgeai.sourcecontrol.application.handler.PushEventHandler;
import com.forgeai.sourcecontrol.domain.model.WebhookDelivery;
import com.forgeai.sourcecontrol.domain.model.WebhookDeliveryStatus;
import com.forgeai.sourcecontrol.domain.repository.WebhookDeliveryRepository;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubSignatureVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookIngestionService {

    private final GitHubSignatureVerifier signatureVerifier;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final PushEventHandler pushEventHandler;
    private final PullRequestEventHandler pullRequestEventHandler;
    private final InstallationEventHandler installationEventHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public WebhookIngestionResult ingestWebhook(String deliveryId,
                                                String eventType,
                                                String signatureHeader,
                                                byte[] payloadBytes) {
        // 1. Verify HMAC SHA-256 signature
        signatureVerifier.verify(payloadBytes, signatureHeader);

        // 2. Check delivery idempotency
        if (webhookDeliveryRepository.existsByDeliveryId(deliveryId)) {
            log.info("Webhook delivery {} has already been processed (idempotency key hit); ignoring duplicate", deliveryId);
            return WebhookIngestionResult.builder()
                    .deliveryId(deliveryId)
                    .status("IDEMPOTENT_IGNORED")
                    .idempotentHit(true)
                    .build();
        }

        // 3. Persist raw delivery payload
        String payloadString = new String(payloadBytes, StandardCharsets.UTF_8);
        WebhookDelivery delivery = WebhookDelivery.builder()
                .deliveryId(deliveryId)
                .eventType(eventType)
                .signature(signatureHeader)
                .payload(payloadString)
                .status(WebhookDeliveryStatus.RECEIVED)
                .createdAt(Instant.now())
                .build();

        WebhookDelivery savedDelivery = webhookDeliveryRepository.save(delivery);

        // 4. Process event downstream
        try {
            JsonNode rootNode = objectMapper.readTree(payloadString);
            processEvent(eventType, rootNode, deliveryId);

            savedDelivery.setStatus(WebhookDeliveryStatus.PROCESSED);
            savedDelivery.setProcessedAt(Instant.now());
            webhookDeliveryRepository.save(savedDelivery);

            return WebhookIngestionResult.builder()
                    .deliveryId(deliveryId)
                    .status("PROCESSED")
                    .idempotentHit(false)
                    .build();
        } catch (org.springframework.dao.DataIntegrityViolationException dive) {
            log.info("Concurrent constraint violation in webhook delivery {}: {}", deliveryId, dive.getMessage());
            throw dive;
        } catch (Exception e) {
            log.error("Failed to process webhook delivery {}: {}", deliveryId, e.getMessage(), e);
            savedDelivery.setStatus(WebhookDeliveryStatus.FAILED);
            webhookDeliveryRepository.save(savedDelivery);
            throw new RuntimeException("Error processing webhook payload", e);
        }
    }

    private void processEvent(String eventType, JsonNode rootNode, String deliveryId) {
        if ("push".equalsIgnoreCase(eventType)) {
            pushEventHandler.handle(rootNode, deliveryId);
        } else if ("pull_request".equalsIgnoreCase(eventType)) {
            pullRequestEventHandler.handle(rootNode, deliveryId);
        } else if ("installation".equalsIgnoreCase(eventType) || "installation_repositories".equalsIgnoreCase(eventType)) {
            installationEventHandler.handle(rootNode, deliveryId);
        } else {
            log.info("Received unhandled GitHub event type: {}", eventType);
        }
    }
}
