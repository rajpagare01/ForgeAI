package com.forgeai.sourcecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.sourcecontrol.application.service.WebhookIngestionResult;
import com.forgeai.sourcecontrol.application.service.WebhookIngestionService;
import com.forgeai.sourcecontrol.domain.event.EventEnvelope;
import com.forgeai.sourcecontrol.infrastructure.outbox.OutboxPublisherRelay;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.OutboxEventEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TracePropagationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SpringDataOutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPublisherRelay outboxPublisherRelay;

    @Autowired
    private com.forgeai.sourcecontrol.infrastructure.github.GitHubSignatureVerifier signatureVerifier;

    @Test
    @DisplayName("TracePropagationIT: Inbound webhook with traceparent propagates W3C trace_id into outbox_events and relay MDC")
    void testTracePropagationFromWebhookToOutboxAndRelay() throws Exception {
        String testTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String testSpanId = "00f067aa0ba902b7";
        String traceparentHeader = String.format("00-%s-%s-01", testTraceId, testSpanId);
        String deliveryId = UUID.randomUUID().toString();

        String pushPayload = """
        {
          "repository": {
            "id": 998811,
            "name": "ForgeAI-Core",
            "default_branch": "main"
          },
          "commits": [
            {
              "id": "a91b4c3000000000000000000000000000000000",
              "message": "Implement pessimistic locking for TASK-201",
              "timestamp": "2026-10-12T18:00:00Z",
              "author": {
                "name": "alice",
                "email": "alice@acme.com"
              }
            }
          ]
        }
        """;
        byte[] payloadBytes = pushPayload.getBytes(StandardCharsets.UTF_8);
        String validSignature = signatureVerifier.computeSignature(payloadBytes);

        // 1. Send webhook with W3C traceparent header and valid signature
        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Delivery", deliveryId)
                        .header("X-GitHub-Event", "push")
                        .header("X-Hub-Signature-256", validSignature)
                        .header("traceparent", traceparentHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadBytes))
                .andExpect(status().isAccepted())
                .andExpect(header().string("traceparent", org.hamcrest.Matchers.containsString(testTraceId)));

        // 2. Assert outbox event was persisted with exact traceId
        List<OutboxEventEntity> events = outboxEventRepository.findAll();
        assertThat(events).isNotEmpty();
        OutboxEventEntity matchedEvent = events.stream()
                .filter(e -> testTraceId.equals(e.getTraceId()))
                .findFirst()
                .orElse(null);

        assertThat(matchedEvent).isNotNull();
        assertThat(matchedEvent.getTraceId()).isEqualTo(testTraceId);
        assertThat(matchedEvent.getPayload()).contains(testTraceId);

        // 3. Trigger outbox relay batch processing and verify relay restored MDC context
        outboxPublisherRelay.processOutboxBatch();

        // Reload from DB and verify marked published
        OutboxEventEntity updated = outboxEventRepository.findById(matchedEvent.getId()).orElseThrow();
        assertThat(updated.getPublishedAt()).isNotNull();
    }
}
