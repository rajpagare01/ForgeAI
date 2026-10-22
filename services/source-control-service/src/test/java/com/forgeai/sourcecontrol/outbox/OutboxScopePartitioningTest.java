package com.forgeai.sourcecontrol.outbox;

import com.forgeai.sourcecontrol.domain.event.DomainEvent;
import com.forgeai.sourcecontrol.domain.event.RepositoryConnectedEvent;
import com.forgeai.sourcecontrol.infrastructure.outbox.OutboxService;
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
public class OutboxScopePartitioningTest {

    @Mock
    private SpringDataOutboxEventRepository outboxEventRepository;

    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        outboxService = new OutboxService(outboxEventRepository);
        lenient().when(outboxEventRepository.save(any(OutboxEventEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("SEC-WS-05: Valid ORGANIZATION event persists with organizationId and scope=ORGANIZATION")
    void testValidOrganizationScopeOutboxEvent() {
        UUID orgId = UUID.randomUUID();
        RepositoryConnectedEvent event = RepositoryConnectedEvent.builder()
                .organizationId(orgId)
                .workspaceId(orgId)
                .repositoryId(UUID.randomUUID())
                .fullName("forgeai/forgeai-core")
                .defaultBranch("main")
                .timestamp(Instant.now())
                .build();

        OutboxEventEntity entity = outboxService.publish(event, "trace-123", "REPOSITORY", "repo-1");

        assertThat(entity).isNotNull();
        assertThat(entity.getScope()).isEqualTo("ORGANIZATION");
        assertThat(entity.getOrganizationId()).isEqualTo(orgId);
        assertThat(entity.getWorkspaceId()).isEqualTo(orgId);

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getScope()).isEqualTo("ORGANIZATION");
        assertThat(captor.getValue().getOrganizationId()).isEqualTo(orgId);
    }

    @Test
    @DisplayName("SEC-WS-05: ORGANIZATION event without organizationId is rejected (fails closed)")
    void testOrganizationScopeMissingOrgIdRejected() {
        // An event explicitly specifying ORGANIZATION scope with null organizationId
        DomainEvent event = new DomainEvent() {
            @Override
            public String getEventType() {
                return "TEST_ORG_EVENT";
            }

            @Override
            public Instant getTimestamp() {
                return Instant.now();
            }

            @Override
            public UUID getOrganizationId() {
                return null;
            }

            @Override
            public String getScope() {
                return "ORGANIZATION";
            }
        };

        assertThatThrownBy(() -> outboxService.publish(event, "trace-456", "REPOSITORY", "repo-2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a non-null organizationId");

        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("SEC-WS-05: Valid PERSONAL event persists with workspaceId and scope=PERSONAL without organizationId")
    void testValidPersonalScopeOutboxEvent() {
        UUID personalWsId = UUID.randomUUID();
        RepositoryConnectedEvent event = RepositoryConnectedEvent.builder()
                .organizationId(null)
                .workspaceId(personalWsId)
                .repositoryId(UUID.randomUUID())
                .fullName("alice/personal-tool")
                .defaultBranch("main")
                .timestamp(Instant.now())
                .build();

        OutboxEventEntity entity = outboxService.publish(event, "trace-789", "REPOSITORY", "repo-3");

        assertThat(entity).isNotNull();
        assertThat(entity.getScope()).isEqualTo("PERSONAL");
        assertThat(entity.getOrganizationId()).isNull();
        assertThat(entity.getWorkspaceId()).isEqualTo(personalWsId);

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getScope()).isEqualTo("PERSONAL");
        assertThat(captor.getValue().getOrganizationId()).isNull();
        assertThat(captor.getValue().getWorkspaceId()).isEqualTo(personalWsId);
    }

    @Test
    @DisplayName("SEC-WS-05: PERSONAL event without workspaceId is rejected (fails closed)")
    void testPersonalScopeMissingWorkspaceIdRejected() {
        RepositoryConnectedEvent event = RepositoryConnectedEvent.builder()
                .organizationId(null)
                .workspaceId(null)
                .repositoryId(UUID.randomUUID())
                .fullName("alice/broken-repo")
                .defaultBranch("main")
                .timestamp(Instant.now())
                .build();

        assertThatThrownBy(() -> outboxService.publish(event, "trace-999", "REPOSITORY", "repo-4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a non-null workspaceId");

        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("SEC-WS-05: PERSONAL event with non-null organizationId is rejected (fails closed)")
    void testPersonalScopeWithOrgIdRejected() {
        UUID personalWsId = UUID.randomUUID();
        UUID rogueOrgId = UUID.randomUUID();
        DomainEvent rogueEvent = new DomainEvent() {
            @Override
            public String getEventType() { return "ROGUE_PERSONAL"; }
            @Override
            public Instant getTimestamp() { return Instant.now(); }
            @Override
            public UUID getOrganizationId() { return rogueOrgId; }
            @Override
            public UUID getWorkspaceId() { return personalWsId; }
            @Override
            public String getScope() { return "PERSONAL"; }
        };

        assertThatThrownBy(() -> outboxService.publish(rogueEvent, "trace-rogue", "REPOSITORY", "repo-rogue"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must have null organizationId");

        verify(outboxEventRepository, never()).save(any());
    }
}
