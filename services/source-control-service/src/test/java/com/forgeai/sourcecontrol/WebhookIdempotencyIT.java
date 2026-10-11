package com.forgeai.sourcecontrol;

import com.forgeai.sourcecontrol.infrastructure.github.GitHubSignatureVerifier;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataWebhookDeliveryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookIdempotencyIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GitHubSignatureVerifier signatureVerifier;

    @Autowired
    private SpringDataWebhookDeliveryRepository webhookDeliveryRepository;

    @Autowired
    private SpringDataOutboxEventRepository outboxEventRepository;

    @Test
    @DisplayName("WebhookIdempotencyIT: Posting the same delivery ID twice creates only one database record and avoids duplicate events")
    void sameDeliveryIdReturnsIdempotentAckWithoutDuplicateProcessing() throws Exception {
        String deliveryId = "deliv-" + UUID.randomUUID();
        String payload = """
            {
                "action": "opened",
                "repository": {
                    "id": 99901,
                    "name": "forgeai-core",
                    "full_name": "forgeai/forgeai-core",
                    "default_branch": "main",
                    "private": true
                },
                "pull_request": {
                    "id": 88801,
                    "number": 1,
                    "title": "Initial Setup PR",
                    "body": "Setup initial scaffolding",
                    "head": {"ref": "feature/setup"},
                    "base": {"ref": "main"},
                    "user": {"login": "octocat"},
                    "merged": false
                }
            }
            """;
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String signature = signatureVerifier.computeSignature(payloadBytes);

        long initialDeliveriesCount = webhookDeliveryRepository.count();
        long initialOutboxCount = outboxEventRepository.count();

        // 1. First POST
        mockMvc.perform(post("/api/v1/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Delivery", deliveryId)
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-Hub-Signature-256", signature)
                        .content(payloadBytes))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSED"))
                .andExpect(jsonPath("$.idempotentHit").value(false));

        assertThat(webhookDeliveryRepository.count()).isEqualTo(initialDeliveriesCount + 1);
        long outboxCountAfterFirst = outboxEventRepository.count();
        assertThat(outboxCountAfterFirst).isEqualTo(initialOutboxCount + 1);

        // 2. Second POST with identical delivery ID
        mockMvc.perform(post("/api/v1/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Delivery", deliveryId)
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-Hub-Signature-256", signature)
                        .content(payloadBytes))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("IDEMPOTENT_IGNORED"))
                .andExpect(jsonPath("$.idempotentHit").value(true));

        // Assert no additional rows were created in webhook_deliveries or outbox_events
        assertThat(webhookDeliveryRepository.count()).isEqualTo(initialDeliveriesCount + 1);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxCountAfterFirst);
    }
}
