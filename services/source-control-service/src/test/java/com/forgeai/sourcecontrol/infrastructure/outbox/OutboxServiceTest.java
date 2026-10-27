package com.forgeai.sourcecontrol.infrastructure.outbox;

import com.forgeai.sourcecontrol.domain.event.DomainEvent;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.OutboxEventEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock
    private SpringDataOutboxEventRepository outboxEventRepository;

    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        outboxService = new OutboxService(outboxEventRepository);
    }

    private record TestDomainEvent(String eventType, Instant timestamp, UUID organizationId) implements DomainEvent {
        @Override
        public String getEventType() {
            return eventType;
        }

        @Override
        public Instant getTimestamp() {
            return timestamp;
        }

        @Override
        public UUID getOrganizationId() {
            return organizationId;
        }

        @Override
        public String getScope() {
            return "ORGANIZATION";
        }
    }

    @Test
    @DisplayName("OutboxService: event with valid organizationId persists successfully with non-null organizationId")
    void validTenantEventPersistsSuccessfully() {
        UUID orgId = UUID.randomUUID();
        TestDomainEvent event = new TestDomainEvent("REPOSITORY_CONNECTED", Instant.now(), orgId);

        when(outboxEventRepository.save(any(OutboxEventEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        OutboxEventEntity result = outboxService.publish(event, "trace-123", "Repository", "repo-456");

        assertThat(result).isNotNull();
        assertThat(result.getOrganizationId()).isEqualTo(orgId);
        assertThat(result.getEventType()).isEqualTo("REPOSITORY_CONNECTED");

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId()).isEqualTo(orgId);
    }

    @Test
    @DisplayName("OutboxService: event with NULL organizationId is rejected with IllegalArgumentException and NOT persisted")
    void eventWithNullOrganizationIdFailsFast() {
        TestDomainEvent invalidEvent = new TestDomainEvent("UNOWNED_EVENT", Instant.now(), null);

        assertThatThrownBy(() -> outboxService.publish(invalidEvent, "trace-123", "Aggregate", "agg-456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a non-null organizationId");

        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    @DisplayName("OutboxService: null event is rejected with IllegalArgumentException")
    void nullEventFailsFast() {
        assertThatThrownBy(() -> outboxService.publish(null, "trace-123", "Aggregate", "agg-456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");

        verifyNoInteractions(outboxEventRepository);
    }
}
