package com.forgeai.project.infrastructure.messaging;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.project.domain.event.BranchCreatedEvent;
import com.forgeai.project.domain.event.CommitCreatedEvent;
import com.forgeai.project.domain.event.PullRequestOpenedEvent;
import com.forgeai.project.domain.event.UserCreatedEvent;
import com.forgeai.project.domain.event.UserUpdatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@ConditionalOnProperty(name = "forgeai.rabbitmq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqEventConsumer {

    private final IdentityEventConsumer identityEventConsumer;
    private final SourceControlEventConsumer sourceControlEventConsumer;
    private final ObjectMapper objectMapper;

    public RabbitMqEventConsumer(
            IdentityEventConsumer identityEventConsumer,
            SourceControlEventConsumer sourceControlEventConsumer) {
        this.identityEventConsumer = identityEventConsumer;
        this.sourceControlEventConsumer = sourceControlEventConsumer;
        this.objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @RabbitListener(queues = "forgeai.project.identity.queue")
    public void onIdentityEvent(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            JsonNode root = objectMapper.readTree(body);
            String eventType = root.path("eventType").asText("");
            String traceId = root.path("traceId").asText(null);
            if (traceId != null && !traceId.isBlank()) {
                MDC.put("traceId", traceId);
            }
            JsonNode payloadNode = root.hasNonNull("payload") ? root.path("payload") : root;

            log.info("[RabbitMQ Consumer] Received identity event: type={}, traceId={}", eventType, traceId);
            if ("USER_CREATED".equalsIgnoreCase(eventType)) {
                UserCreatedEvent event = objectMapper.treeToValue(payloadNode, UserCreatedEvent.class);
                identityEventConsumer.onUserCreated(event);
            } else if ("USER_UPDATED".equalsIgnoreCase(eventType)) {
                UserUpdatedEvent event = objectMapper.treeToValue(payloadNode, UserUpdatedEvent.class);
                identityEventConsumer.onUserUpdated(event);
            }
        } catch (Exception e) {
            log.error("[RabbitMQ Consumer] Failed to process identity event: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process identity event", e);
        } finally {
            MDC.remove("traceId");
        }
    }

    @RabbitListener(queues = "forgeai.project.sourcecontrol.queue")
    public void onSourceControlEvent(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            JsonNode root = objectMapper.readTree(body);
            String eventType = root.path("eventType").asText("");
            String traceId = root.path("traceId").asText(null);
            if (traceId != null && !traceId.isBlank()) {
                MDC.put("traceId", traceId);
            }
            JsonNode payloadNode = root.hasNonNull("payload") ? root.path("payload") : root;

            log.info("[RabbitMQ Consumer] Received source-control event: type={}, traceId={}", eventType, traceId);
            if ("COMMIT_CREATED".equalsIgnoreCase(eventType)) {
                CommitCreatedEvent event = objectMapper.treeToValue(payloadNode, CommitCreatedEvent.class);
                sourceControlEventConsumer.onCommitCreated(event);
            } else if ("PULL_REQUEST_OPENED".equalsIgnoreCase(eventType)) {
                PullRequestOpenedEvent event = objectMapper.treeToValue(payloadNode, PullRequestOpenedEvent.class);
                sourceControlEventConsumer.onPullRequestOpened(event);
            } else if ("BRANCH_CREATED".equalsIgnoreCase(eventType)) {
                BranchCreatedEvent event = objectMapper.treeToValue(payloadNode, BranchCreatedEvent.class);
                sourceControlEventConsumer.onBranchCreated(event);
            } else if ("PULL_REQUEST_MERGED".equalsIgnoreCase(eventType)) {
                java.util.Map<String, Object> payload = objectMapper.convertValue(
                        payloadNode, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
                sourceControlEventConsumer.onPullRequestMerged(payload);
            }
        } catch (Exception e) {
            log.error("[RabbitMQ Consumer] Failed to process source-control event: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process source-control event", e);
        } finally {
            MDC.remove("traceId");
        }
    }
}

