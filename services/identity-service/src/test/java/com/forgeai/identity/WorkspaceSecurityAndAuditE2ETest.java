package com.forgeai.identity;

import com.forgeai.identity.application.port.out.OrganizationMembershipRepository;
import com.forgeai.identity.application.port.out.OrganizationRepository;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.application.port.out.WorkspaceRepository;
import com.forgeai.identity.application.service.AuditService;
import com.forgeai.identity.application.service.WorkspaceService;
import com.forgeai.identity.domain.model.Workspace;
import com.forgeai.identity.domain.model.WorkspaceType;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.AuditEventEntity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.repository.SpringDataAuditEventRepository;
import com.forgeai.identity.infrastructure.logging.StructuredLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class WorkspaceSecurityAndAuditE2ETest {

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private OrganizationMembershipRepository organizationMembershipRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SpringDataAuditEventRepository auditEventRepository;

    @Mock
    private StructuredLogger structuredLogger;

    private WorkspaceService workspaceService;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(
                workspaceRepository,
                organizationMembershipRepository,
                organizationRepository,
                userRepository
        );

        auditService = new AuditService(
                auditEventRepository,
                structuredLogger
        );
    }

    @Test
    @DisplayName("E2E-WS-04: Personal action records audit event with null organizationId and valid workspaceId")
    void testPersonalAuditEventRecordedWithNullOrgIdAndValidWorkspaceId() {
        UUID personalWsId = UUID.randomUUID();
        UUID userA = UUID.randomUUID();

        when(auditEventRepository.save(any(AuditEventEntity.class)))
                .thenAnswer(inv -> {
                    AuditEventEntity e = inv.getArgument(0);
                    e.setId(UUID.randomUUID());
                    return e;
                });

        AuditEventEntity recorded = auditService.recordEvent(
                null, // organizationId is null for personal workspace
                personalWsId,
                userA.toString(),
                "WORKSPACE_ACCESSED",
                "WORKSPACE",
                personalWsId.toString(),
                "VIEW",
                "SUCCESS",
                Map.of("client", "web-dashboard")
        );

        assertThat(recorded).isNotNull();
        assertThat(recorded.getOrganizationId()).isNull();
        assertThat(recorded.getWorkspaceId()).isEqualTo(personalWsId);
        assertThat(recorded.getActorUserId()).isEqualTo(userA.toString());
        assertThat(recorded.getEventType()).isEqualTo("WORKSPACE_ACCESSED");

        ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
        verify(auditEventRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId()).isNull();
        assertThat(captor.getValue().getWorkspaceId()).isEqualTo(personalWsId);
    }

    @Test
    @DisplayName("E2E-WS-04: Organization action records audit event with organizationId equals workspaceId")
    void testOrganizationAuditEventRecordedWithMatchingOrgAndWorkspace() {
        UUID orgId = UUID.randomUUID();
        UUID userA = UUID.randomUUID();

        when(auditEventRepository.save(any(AuditEventEntity.class)))
                .thenAnswer(inv -> {
                    AuditEventEntity e = inv.getArgument(0);
                    e.setId(UUID.randomUUID());
                    return e;
                });

        AuditEventEntity recorded = auditService.recordEvent(
                orgId,
                orgId,
                userA.toString(),
                "MEMBER_INVITED",
                "ORGANIZATION",
                orgId.toString(),
                "INVITE",
                "SUCCESS",
                Map.of("role", "DEVELOPER")
        );

        assertThat(recorded).isNotNull();
        assertThat(recorded.getOrganizationId()).isEqualTo(orgId);
        assertThat(recorded.getWorkspaceId()).isEqualTo(orgId);
        assertThat(recorded.getActorUserId()).isEqualTo(userA.toString());
    }

    @Test
    @DisplayName("E2E-WS-04: Querying audit events by workspace filters strictly to that workspace partition")
    void testAuditQueryByWorkspaceFiltersStrictly() {
        UUID personalWsA = UUID.randomUUID();
        UUID personalWsB = UUID.randomUUID();

        AuditEventEntity eventA = AuditEventEntity.builder()
                .id(UUID.randomUUID())
                .workspaceId(personalWsA)
                .organizationId(null)
                .eventType("PROJECT_CREATED")
                .createdAt(Instant.now())
                .build();

        when(auditEventRepository.findByWorkspaceIdOrderByCreatedAtDesc(eq(personalWsA), any(PageRequest.class)))
                .thenReturn(List.of(eventA));

        List<AuditEventEntity> results = auditService.getRecentEventsForWorkspace(personalWsA, 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getWorkspaceId()).isEqualTo(personalWsA);
        assertThat(results.get(0).getOrganizationId()).isNull();
        verify(auditEventRepository).findByWorkspaceIdOrderByCreatedAtDesc(eq(personalWsA), any(PageRequest.class));
        verify(auditEventRepository, never()).findByWorkspaceIdOrderByCreatedAtDesc(eq(personalWsB), any(PageRequest.class));
    }

    @Test
    @DisplayName("E2E-WS-12: Concurrent personal workspace provisioning handles race condition idempotently")
    void testConcurrentPersonalWorkspaceProvisioningHandledIdempotently() throws Exception {
        UUID userId = UUID.randomUUID();
        String username = "multithread-user";

        UUID expectedWsId = UUID.randomUUID();
        Workspace existingWorkspace = Workspace.builder()
                .id(expectedWsId)
                .type(WorkspaceType.PERSONAL)
                .ownerUserId(userId)
                .organizationId(null)
                .name(username + "'s Workspace")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        // Simulate thread race: first call sees empty, attempts save which throws constraint violation,
        // then falls back to fetching existing workspace.
        AtomicInteger invocationCount = new AtomicInteger(0);
        when(workspaceRepository.findByTypeAndOwnerUserId(WorkspaceType.PERSONAL, userId))
                .thenAnswer(inv -> {
                    if (invocationCount.getAndIncrement() == 0) {
                        return Optional.empty();
                    }
                    return Optional.of(existingWorkspace);
                });

        when(workspaceRepository.save(any(Workspace.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint 'uq_workspaces_owner_personal'"));

        Workspace result = workspaceService.getOrCreatePersonalWorkspace(userId, username);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(expectedWsId);
        assertThat(result.getType()).isEqualTo(WorkspaceType.PERSONAL);
        assertThat(result.getOrganizationId()).isNull();
        assertThat(result.getOwnerUserId()).isEqualTo(userId);
    }
}
