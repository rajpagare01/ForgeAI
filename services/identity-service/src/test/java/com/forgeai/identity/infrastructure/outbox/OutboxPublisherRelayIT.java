package com.forgeai.identity.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.identity.domain.event.EventEnvelope;
import com.forgeai.identity.domain.event.UserCreatedEvent;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.OutboxEventEntity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.repository.OutboxEventRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class OutboxPublisherRelayIT {

    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @BeforeAll
    static void beforeAll() {
        postgres.start();
    }

    @AfterAll
    static void afterAll() {
        postgres.stop();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        // Disable automatic scheduled execution during tests so we can trigger relay runs deterministically
        registry.add("outbox.poll.interval-ms", () -> "3600000");
    }

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxPublisherRelay outboxPublisherRelay;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockBean
    private EventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        reset(eventPublisher);
    }

    @Test
    @DisplayName("Transactional Rollback Test: Outbox event rolled back when domain transaction fails")
    void shouldRollbackOutboxEventWhenTransactionFails() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        assertThrows(RuntimeException.class, () -> {
            txTemplate.execute(status -> {
                UUID userId = UUID.randomUUID();
                UserCreatedEvent event = UserCreatedEvent.builder()
                        .userId(userId)
                        .email("rollback@example.com")
                        .firstName("John")
                        .lastName("Doe")
                        .build();

                outboxService.publish(event, "trace-rollback-123");

                // Simulate domain failure triggering rollback
                throw new RuntimeException("Simulated domain processing failure");
            });
        });

        List<OutboxEventEntity> allEvents = outboxEventRepository.findAll();
        assertThat(allEvents).isEmpty();
    }

    @Test
    @DisplayName("Successful Relay Dispatch: Unprocessed event is dispatched and published_at is stamped")
    void shouldDispatchUnprocessedEventAndStampPublishedAt() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        UUID userId = UUID.randomUUID();

        txTemplate.executeWithoutResult(status -> {
            UserCreatedEvent event = UserCreatedEvent.builder()
                    .userId(userId)
                    .email("dispatch@example.com")
                    .firstName("Jane")
                    .lastName("Doe")
                    .build();
            outboxService.publish(event, "trace-dispatch-1");
        });

        // Verify inserted row is pending
        List<OutboxEventEntity> pendingBefore = outboxEventRepository.findAll();
        assertThat(pendingBefore).hasSize(1);
        assertThat(pendingBefore.get(0).getPublishedAt()).isNull();
        assertThat(pendingBefore.get(0).getAttemptCount()).isZero();

        // Trigger relay batch
        int processedCount = outboxPublisherRelay.processBatchInternal();
        assertThat(processedCount).isEqualTo(1);

        // Verify dispatch via EventPublisher
        verify(eventPublisher, times(1)).publish(any(EventEnvelope.class));

        // Verify entity updated in DB
        OutboxEventEntity updated = outboxEventRepository.findById(pendingBefore.get(0).getId()).orElseThrow();
        assertThat(updated.getPublishedAt()).isNotNull();
        assertThat(updated.getLastError()).isNull();
    }

    @Test
    @DisplayName("Poison Pill Handling: Failing event increments attempt_count, records last_error, and dead-letters after max_attempts")
    void shouldHandlePoisonPillAndNotBlockSubsequentEvents() {
        // Set eventPublisher to fail on poison event
        doThrow(new RuntimeException("Downstream broker connection timeout"))
                .when(eventPublisher).publish(any(EventEnvelope.class));

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        UUID poisonUserId = UUID.randomUUID();

        txTemplate.executeWithoutResult(status -> {
            UserCreatedEvent event = UserCreatedEvent.builder()
                    .userId(poisonUserId)
                    .email("poison@example.com")
                    .firstName("Poison")
                    .lastName("Pill")
                    .build();
            outboxService.publish(event, "trace-poison-1");
        });

        OutboxEventEntity poisonEntity = outboxEventRepository.findAll().get(0);

        // Execute batch relay 5 times (max attempts = 5)
        for (int i = 1; i <= 5; i++) {
            outboxPublisherRelay.processBatchInternal();
            OutboxEventEntity current = outboxEventRepository.findById(poisonEntity.getId()).orElseThrow();
            assertThat(current.getAttemptCount()).isEqualTo(i);
            assertThat(current.getLastError()).contains("Downstream broker connection timeout");
            assertThat(current.getPublishedAt()).isNull();
        }

        // On 6th run, attempt_count == 5 >= maxAttempts, so SELECT ... FOR UPDATE SKIP LOCKED ignores it
        int processedAfterMax = outboxPublisherRelay.processBatchInternal();
        assertThat(processedAfterMax).isZero();

        // Now insert a second valid event and fix the publisher
        reset(eventPublisher);
        UUID validUserId = UUID.randomUUID();
        txTemplate.executeWithoutResult(status -> {
            UserCreatedEvent event = UserCreatedEvent.builder()
                    .userId(validUserId)
                    .email("valid@example.com")
                    .firstName("Valid")
                    .lastName("Event")
                    .build();
            outboxService.publish(event, "trace-valid-2");
        });

        // Verify valid event is processed even though poison event remains dead-lettered
        int processedValid = outboxPublisherRelay.processBatchInternal();
        assertThat(processedValid).isEqualTo(1);
        verify(eventPublisher, times(1)).publish(any(EventEnvelope.class));
    }

    @Test
    @DisplayName("Concurrency Test: Concurrent workers using SELECT FOR UPDATE SKIP LOCKED do not double-publish")
    void shouldPreventDoublePublishingUnderConcurrentWorkers() throws InterruptedException, ExecutionException {
        int eventCount = 20;
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        txTemplate.executeWithoutResult(status -> {
            for (int i = 0; i < eventCount; i++) {
                UserCreatedEvent event = UserCreatedEvent.builder()
                        .userId(UUID.randomUUID())
                        .email("concurrent" + i + "@example.com")
                        .firstName("User" + i)
                        .lastName("Concurrent")
                        .build();
                outboxService.publish(event, "trace-conc-" + i);
            }
        });

        ConcurrentHashMap<String, AtomicInteger> dispatchesByEventId = new ConcurrentHashMap<>();
        doAnswer(invocation -> {
            EventEnvelope<?> envelope = invocation.getArgument(0);
            dispatchesByEventId.computeIfAbsent(envelope.getEventId().toString(), k -> new AtomicInteger(0))
                    .incrementAndGet();
            // Simulate brief network delay
            Thread.sleep(15);
            return null;
        }).when(eventPublisher).publish(any(EventEnvelope.class));

        // Simulate 4 concurrent worker threads polling simultaneously
        int workerThreads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(workerThreads);
        List<Callable<Integer>> tasks = new ArrayList<>();

        for (int i = 0; i < workerThreads; i++) {
            tasks.add(() -> {
                int totalProcessed = 0;
                for (int round = 0; round < 10; round++) {
                    totalProcessed += outboxPublisherRelay.processBatchInternal();
                }
                return totalProcessed;
            });
        }

        List<Future<Integer>> futures = executor.invokeAll(tasks);
        int totalProcessedSum = 0;
        for (Future<Integer> future : futures) {
            totalProcessedSum += future.get();
        }
        executor.shutdown();

        // All 20 events should have been processed exactly once across workers
        assertThat(totalProcessedSum).isEqualTo(eventCount);
        assertThat(dispatchesByEventId).hasSize(eventCount);
        for (AtomicInteger count : dispatchesByEventId.values()) {
            assertThat(count.get()).isEqualTo(1);
        }

        // Verify all 20 rows in DB have published_at set
        List<OutboxEventEntity> allEntities = outboxEventRepository.findAll();
        assertThat(allEntities).hasSize(eventCount);
        assertThat(allEntities).allMatch(e -> e.getPublishedAt() != null);
    }
}
