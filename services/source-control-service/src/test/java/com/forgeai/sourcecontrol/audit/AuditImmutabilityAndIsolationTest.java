package com.forgeai.sourcecontrol.audit;

import com.forgeai.sourcecontrol.application.service.AuditService;
import com.forgeai.sourcecontrol.infrastructure.logging.StructuredLogger;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.AuditEventEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataAuditEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AuditImmutabilityAndIsolationTest {

    @Test
    @DisplayName("Audit repository must enforce immutability by rejecting delete operations")
    void testAuditRepositoryImmutability() {
        SpringDataAuditEventRepository repo = Mockito.mock(SpringDataAuditEventRepository.class, Mockito.CALLS_REAL_METHODS);
        AuditEventEntity entity = AuditEventEntity.builder().id(UUID.randomUUID()).build();

        assertThatThrownBy(() -> repo.delete(entity))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("immutable");

        assertThatThrownBy(() -> repo.deleteById(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("immutable");

        assertThatThrownBy(repo::deleteAll)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("Audit service must scope queries strictly to the tenant organizationId")
    void testAuditTenantIsolation() {
        SpringDataAuditEventRepository repo = Mockito.mock(SpringDataAuditEventRepository.class);
        StructuredLogger logger = Mockito.mock(StructuredLogger.class);
        AuditService auditService = new AuditService(repo, logger);

        UUID tenant1 = UUID.randomUUID();
        UUID tenant2 = UUID.randomUUID();

        AuditEventEntity event1 = AuditEventEntity.builder()
                .id(UUID.randomUUID())
                .organizationId(tenant1)
                .eventType("github.installation.bound")
                .createdAt(Instant.now())
                .build();

        when(repo.findByOrganizationIdOrderByCreatedAtDesc(eq(tenant1), any(Pageable.class)))
                .thenReturn(List.of(event1));
        when(repo.findByOrganizationIdOrderByCreatedAtDesc(eq(tenant2), any(Pageable.class)))
                .thenReturn(List.of());

        List<AuditEventEntity> tenant1Events = auditService.getRecentEventsForTenant(tenant1, 10);
        assertThat(tenant1Events).hasSize(1);
        assertThat(tenant1Events.get(0).getOrganizationId()).isEqualTo(tenant1);

        List<AuditEventEntity> tenant2Events = auditService.getRecentEventsForTenant(tenant2, 10);
        assertThat(tenant2Events).isEmpty();

        verify(repo).findByOrganizationIdOrderByCreatedAtDesc(eq(tenant1), any(Pageable.class));
        verify(repo).findByOrganizationIdOrderByCreatedAtDesc(eq(tenant2), any(Pageable.class));
    }

    @Test
    @DisplayName("Audit service records event with correct metadata and logs structured entry")
    void testAuditServiceRecordEvent() {
        SpringDataAuditEventRepository repo = Mockito.mock(SpringDataAuditEventRepository.class);
        StructuredLogger logger = Mockito.mock(StructuredLogger.class);
        AuditService auditService = new AuditService(repo, logger);

        UUID orgId = UUID.randomUUID();
        when(repo.save(any(AuditEventEntity.class))).thenAnswer(invocation -> {
            AuditEventEntity e = invocation.getArgument(0);
            return AuditEventEntity.builder()
                    .id(UUID.randomUUID())
                    .organizationId(e.getOrganizationId())
                    .actorUserId(e.getActorUserId())
                    .eventType(e.getEventType())
                    .entityType(e.getEntityType())
                    .entityId(e.getEntityId())
                    .action(e.getAction())
                    .outcome(e.getOutcome())
                    .metadata(e.getMetadata())
                    .createdAt(e.getCreatedAt())
                    .build();
        });

        AuditEventEntity recorded = auditService.recordEvent(
                orgId,
                "user-123",
                "github.installation.claimed",
                "INSTALLATION",
                "inst-999",
                "CLAIM",
                "SUCCESS",
                Map.of("installationId", 999L)
        );

        assertThat(recorded).isNotNull();
        assertThat(recorded.getOrganizationId()).isEqualTo(orgId);
        assertThat(recorded.getEventType()).isEqualTo("github.installation.claimed");
        assertThat(recorded.getAction()).isEqualTo("CLAIM");
        assertThat(recorded.getOutcome()).isEqualTo("SUCCESS");

        verify(repo).save(any(AuditEventEntity.class));
    }
}
