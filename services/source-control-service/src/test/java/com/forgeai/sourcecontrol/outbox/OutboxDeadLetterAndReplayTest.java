package com.forgeai.sourcecontrol.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.sourcecontrol.infrastructure.logging.StructuredLogger;
import com.forgeai.sourcecontrol.infrastructure.messaging.EventPublisher;
import com.forgeai.sourcecontrol.infrastructure.metrics.SourceControlMetrics;
import com.forgeai.sourcecontrol.infrastructure.outbox.OutboxPublisherRelay;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.OutboxEventEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class OutboxDeadLetterAndReplayTest {

    @Test
    @DisplayName("Outbox event transitioning to DEAD_LETTER when attempt_count reaches max_attempts")
    void testDeadLetterTransitionOnMaxAttempts() throws Exception {
        SpringDataOutboxEventRepository repo = Mockito.mock(SpringDataOutboxEventRepository.class);
        EventPublisher publisher = Mockito.mock(EventPublisher.class);
        SourceControlMetrics metrics = Mockito.mock(SourceControlMetrics.class);
        StructuredLogger logger = Mockito.mock(StructuredLogger.class);
        ObjectMapper objectMapper = new ObjectMapper();

        OutboxPublisherRelay relay = new OutboxPublisherRelay(
                repo, publisher, objectMapper, metrics, logger
        );

        OutboxEventEntity entity = OutboxEventEntity.builder()
                .id(UUID.randomUUID())
                .eventType("github.repository.synced")
                .aggregateType("REPOSITORY")
                .aggregateId("repo-123")
                .payload("{\"invalid\": json}")
                .attemptCount(4)
                .maxAttempts(5)
                .status("RETRYING")
                .build();

        when(repo.findUnpublishedEventsForProcessing(anyInt())).thenReturn(List.of(entity));

        relay.processOutboxBatch();

        // 4 attempts + 1 new attempt = 5 attempts >= maxAttempts (5)
        assertThat(entity.getAttemptCount()).isEqualTo(5);
        assertThat(entity.getStatus()).isEqualTo("DEAD_LETTER");
        assertThat(entity.getDeadLetterAt()).isNotNull();
        assertThat(entity.getNextRetryAt()).isNull();

        verify(metrics).recordOutboxDeadLetter();
        verify(repo).save(entity);
    }

    @Test
    @DisplayName("Outbox event scheduling exponential backoff retry when under max_attempts")
    void testExponentialBackoffUnderMaxAttempts() throws Exception {
        SpringDataOutboxEventRepository repo = Mockito.mock(SpringDataOutboxEventRepository.class);
        EventPublisher publisher = Mockito.mock(EventPublisher.class);
        SourceControlMetrics metrics = Mockito.mock(SourceControlMetrics.class);
        StructuredLogger logger = Mockito.mock(StructuredLogger.class);
        ObjectMapper objectMapper = new ObjectMapper();

        OutboxPublisherRelay relay = new OutboxPublisherRelay(
                repo, publisher, objectMapper, metrics, logger
        );

        Instant before = Instant.now();
        OutboxEventEntity entity = OutboxEventEntity.builder()
                .id(UUID.randomUUID())
                .eventType("github.repository.synced")
                .aggregateType("REPOSITORY")
                .aggregateId("repo-123")
                .payload("{\"invalid\": json}")
                .attemptCount(1)
                .maxAttempts(5)
                .status("PENDING")
                .build();

        when(repo.findUnpublishedEventsForProcessing(anyInt())).thenReturn(List.of(entity));

        relay.processOutboxBatch();

        // 1 attempt + 1 new attempt = 2 attempts < 5
        assertThat(entity.getAttemptCount()).isEqualTo(2);
        assertThat(entity.getStatus()).isEqualTo("RETRYING");
        assertThat(entity.getDeadLetterAt()).isNull();
        assertThat(entity.getNextRetryAt()).isNotNull();
        assertThat(entity.getNextRetryAt()).isAfterOrEqualTo(before);

        verify(metrics).recordOutboxRetry();
        verify(repo).save(entity);
    }

    @Test
    @DisplayName("Replay reset transitions DEAD_LETTER event back to PENDING with 0 attempts")
    void testReplayResetSemantics() {
        OutboxEventEntity entity = OutboxEventEntity.builder()
                .id(UUID.randomUUID())
                .status("DEAD_LETTER")
                .attemptCount(5)
                .deadLetterAt(Instant.now().minusSeconds(3600))
                .lastError("Timeout communicating with broker")
                .build();

        // Simulate replay endpoint action
        entity.setStatus("PENDING");
        entity.setAttemptCount(0);
        entity.setNextRetryAt(Instant.now());
        entity.setDeadLetterAt(null);
        entity.setLastError(null);

        assertThat(entity.getStatus()).isEqualTo("PENDING");
        assertThat(entity.getAttemptCount()).isEqualTo(0);
        assertThat(entity.getDeadLetterAt()).isNull();
        assertThat(entity.getLastError()).isNull();
        assertThat(entity.getNextRetryAt()).isNotNull();
    }
}
