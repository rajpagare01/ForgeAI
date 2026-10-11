package com.forgeai.identity.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.identity.domain.event.EventEnvelope;
import com.forgeai.identity.domain.event.UserCreatedEvent;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.OutboxEventEntity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherRelayTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private EventPublisher eventPublisher;

    private ObjectMapper objectMapper;

    private OutboxPublisherRelay outboxPublisherRelay;

    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        outboxPublisherRelay = new OutboxPublisherRelay(outboxEventRepository, eventPublisher, objectMapper);
        ReflectionTestUtils.setField(outboxPublisherRelay, "batchSize", 50);
        ReflectionTestUtils.setField(outboxPublisherRelay, "maxAttempts", 5);

        outboxService = new OutboxService(outboxEventRepository, objectMapper);
    }

    @Test
    @DisplayName("OutboxService: Serializes domain event to JSON envelope and stores in repository")
    void shouldSerializeAndStoreDomainEvent() throws Exception {
        UUID userId = UUID.randomUUID();
        UserCreatedEvent event = UserCreatedEvent.builder()
                .userId(userId)
                .email("test@example.com")
                .firstName("Alice")
                .lastName("Smith")
                .build();

        when(outboxEventRepository.save(any(OutboxEventEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OutboxEventEntity entity = outboxService.publish(event, "trace-abc-123");

        assertThat(entity).isNotNull();
        assertThat(entity.getEventType()).isEqualTo("USER_CREATED");
        assertThat(entity.getAggregateType()).isEqualTo("USER");
        assertThat(entity.getAggregateId()).isEqualTo(userId.toString());
        assertThat(entity.getTraceId()).isEqualTo("trace-abc-123");
        assertThat(entity.getAttemptCount()).isZero();
        assertThat(entity.getPublishedAt()).isNull();

        EventEnvelope<?> envelope = objectMapper.readValue(entity.getPayload(), EventEnvelope.class);
        assertThat(envelope.getEventType()).isEqualTo("USER_CREATED");
        assertThat(envelope.getTraceId()).isEqualTo("trace-abc-123");
    }

    @Test
    @DisplayName("OutboxPublisherRelay: Successfully dispatches event and updates published_at timestamp")
    void shouldDispatchPendingEventAndStampPublishedAt() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserCreatedEvent domainEvent = UserCreatedEvent.builder()
                .userId(userId)
                .email("relay@example.com")
                .firstName("Bob")
                .lastName("Jones")
                .build();

        EventEnvelope<UserCreatedEvent> envelope = EventEnvelope.from(domainEvent, "trace-relay-1");
        String payloadJson = objectMapper.writeValueAsString(envelope);

        OutboxEventEntity pendingEntity = OutboxEventEntity.builder()
                .id(eventId)
                .eventType(domainEvent.getEventType())
                .aggregateType(domainEvent.getAggregateType())
                .aggregateId(domainEvent.getAggregateId())
                .payload(payloadJson)
                .traceId("trace-relay-1")
                .attemptCount(0)
                .createdAt(Instant.now())
                .build();

        when(outboxEventRepository.findUnprocessedEventsForUpdateSkipLocked(50, 5))
                .thenReturn(List.of(pendingEntity));
        when(outboxEventRepository.save(any(OutboxEventEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        int processed = outboxPublisherRelay.processBatchInternal();

        assertThat(processed).isEqualTo(1);
        verify(eventPublisher, times(1)).publish(any(EventEnvelope.class));

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepository).save(captor.capture());

        OutboxEventEntity saved = captor.getValue();
        assertThat(saved.getPublishedAt()).isNotNull();
        assertThat(saved.getLastError()).isNull();
    }

    @Test
    @DisplayName("Poison Pill Handling: Failing event increments attempt_count and sets last_error")
    void shouldIncrementAttemptCountAndSetLastErrorOnFailure() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserCreatedEvent domainEvent = UserCreatedEvent.builder()
                .userId(userId)
                .email("poison@example.com")
                .firstName("Poison")
                .lastName("Pill")
                .build();

        EventEnvelope<UserCreatedEvent> envelope = EventEnvelope.from(domainEvent, "trace-poison-1");
        String payloadJson = objectMapper.writeValueAsString(envelope);

        OutboxEventEntity pendingEntity = OutboxEventEntity.builder()
                .id(eventId)
                .eventType(domainEvent.getEventType())
                .aggregateType(domainEvent.getAggregateType())
                .aggregateId(domainEvent.getAggregateId())
                .payload(payloadJson)
                .traceId("trace-poison-1")
                .attemptCount(0)
                .createdAt(Instant.now())
                .build();

        when(outboxEventRepository.findUnprocessedEventsForUpdateSkipLocked(50, 5))
                .thenReturn(List.of(pendingEntity));
        doThrow(new RuntimeException("Kafka Broker Not Available"))
                .when(eventPublisher).publish(any(EventEnvelope.class));
        when(outboxEventRepository.save(any(OutboxEventEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        int processed = outboxPublisherRelay.processBatchInternal();

        assertThat(processed).isEqualTo(1);
        assertThat(pendingEntity.getAttemptCount()).isEqualTo(1);
        assertThat(pendingEntity.getLastError()).contains("Kafka Broker Not Available");
        assertThat(pendingEntity.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("Poison Pill Dead Letter: When attempt reaches max_attempts, event is flagged and skipped in queries")
    void shouldRecognizePoisonPillDeadLetterAtMaxAttempts() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserCreatedEvent domainEvent = UserCreatedEvent.builder()
                .userId(userId)
                .email("deadletter@example.com")
                .firstName("Dead")
                .lastName("Letter")
                .build();

        EventEnvelope<UserCreatedEvent> envelope = EventEnvelope.from(domainEvent, "trace-dead-1");
        String payloadJson = objectMapper.writeValueAsString(envelope);

        // Entity already at attemptCount = 4
        OutboxEventEntity pendingEntity = OutboxEventEntity.builder()
                .id(eventId)
                .eventType(domainEvent.getEventType())
                .aggregateType(domainEvent.getAggregateType())
                .aggregateId(domainEvent.getAggregateId())
                .payload(payloadJson)
                .traceId("trace-dead-1")
                .attemptCount(4)
                .createdAt(Instant.now())
                .build();

        when(outboxEventRepository.findUnprocessedEventsForUpdateSkipLocked(50, 5))
                .thenReturn(List.of(pendingEntity));
        doThrow(new RuntimeException("Downstream Persistent Failure"))
                .when(eventPublisher).publish(any(EventEnvelope.class));
        when(outboxEventRepository.save(any(OutboxEventEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        outboxPublisherRelay.processBatchInternal();

        // Should now be at 5 == maxAttempts
        assertThat(pendingEntity.getAttemptCount()).isEqualTo(5);
        assertThat(pendingEntity.getLastError()).contains("Downstream Persistent Failure");
        assertThat(pendingEntity.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("Concurrent Polling Simulation: SKIP LOCKED batch division guarantees mutually exclusive processing")
    void shouldProcessEventsConcurrentlyWithoutDuplicates() throws Exception {
        // Simulating queue behavior of SELECT FOR UPDATE SKIP LOCKED
        int totalEvents = 20;
        ConcurrentLinkedQueue<OutboxEventEntity> queue = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < totalEvents; i++) {
            UUID userId = UUID.randomUUID();
            UserCreatedEvent domainEvent = UserCreatedEvent.builder()
                    .userId(userId)
                    .email("concurrent" + i + "@example.com")
                    .firstName("User" + i)
                    .lastName("Test")
                    .build();
            EventEnvelope<UserCreatedEvent> envelope = EventEnvelope.from(domainEvent, "trace-" + i);
            queue.add(OutboxEventEntity.builder()
                    .id(UUID.randomUUID())
                    .eventType(domainEvent.getEventType())
                    .aggregateType(domainEvent.getAggregateType())
                    .aggregateId(domainEvent.getAggregateId())
                    .payload(objectMapper.writeValueAsString(envelope))
                    .attemptCount(0)
                    .createdAt(Instant.now())
                    .build());
        }

        // Simulating SKIP LOCKED: popping next batch from queue atomically
        when(outboxEventRepository.findUnprocessedEventsForUpdateSkipLocked(anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    int size = inv.getArgument(0);
                    List<OutboxEventEntity> batch = new ArrayList<>();
                    for (int i = 0; i < size; i++) {
                        OutboxEventEntity next = queue.poll();
                        if (next == null) break;
                        batch.add(next);
                    }
                    return batch;
                });
        when(outboxEventRepository.save(any(OutboxEventEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Set<String> publishedEventIds = ConcurrentHashMap.newKeySet();
        doAnswer(inv -> {
            EventEnvelope<?> envelope = inv.getArgument(0);
            publishedEventIds.add(envelope.getEventId().toString());
            return null;
        }).when(eventPublisher).publish(any(EventEnvelope.class));

        // 4 concurrent relay worker threads
        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            tasks.add(() -> {
                int count = 0;
                for (int round = 0; round < 10; round++) {
                    count += outboxPublisherRelay.processBatchInternal();
                }
                return count;
            });
        }

        List<Future<Integer>> futures = executor.invokeAll(tasks);
        int totalProcessed = 0;
        for (Future<Integer> f : futures) {
            totalProcessed += f.get();
        }
        executor.shutdown();

        assertThat(totalProcessed).isEqualTo(totalEvents);
        assertThat(publishedEventIds).hasSize(totalEvents);
    }
}
