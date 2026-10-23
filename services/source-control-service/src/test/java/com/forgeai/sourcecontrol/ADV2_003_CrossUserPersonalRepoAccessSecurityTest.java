package com.forgeai.sourcecontrol;

import com.forgeai.sourcecontrol.application.service.AuditService;
import com.forgeai.sourcecontrol.application.service.RepositoryApplicationService;
import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.model.InstallationStatus;
import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.domain.repository.CommitRepository;
import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import com.forgeai.sourcecontrol.domain.repository.PullRequestRepository;
import com.forgeai.sourcecontrol.domain.repository.RepositoryRepository;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAccessDeniedException;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubRepositoryMetadata;
import com.forgeai.sourcecontrol.infrastructure.metrics.SourceControlMetrics;
import com.forgeai.sourcecontrol.infrastructure.outbox.OutboxService;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.AuditEventEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.OutboxEventEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.WorkspaceJpaEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataWorkspaceRepository;
import com.forgeai.sourcecontrol.presentation.controller.SourceControlOperationsController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ADV2-003 & ADV2-005 Regression Test Suite:
 * - ADV2-003: Cross-user personal workspace repository disclosure / hijacking prevention.
 * - ADV2-005: Outbox dead-letter query, stats, and replay isolation for personal workspaces.
 */
@ExtendWith(MockitoExtension.class)
public class ADV2_003_CrossUserPersonalRepoAccessSecurityTest {

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private CommitRepository commitRepository;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private OutboxService outboxService;

    @Mock
    private GitHubAppClient gitHubAppClient;

    @Mock
    private SpringDataWorkspaceRepository workspaceRepository;

    @Mock
    private SpringDataOutboxEventRepository outboxEventRepository;

    @Mock
    private SourceControlMetrics metrics;

    @Mock
    private AuditService auditService;

    private GitHubAppConfig gitHubAppConfig;
    private InMemoryInstallationRepository installationRepository;
    private RepositoryApplicationService repositoryApplicationService;
    private SourceControlOperationsController operationsController;

    private static final UUID USER_A = UUID.randomUUID();
    private static final UUID USER_B = UUID.randomUUID();
    private static final UUID WS_A = UUID.randomUUID();
    private static final UUID WS_B = UUID.randomUUID();

    static class InMemoryInstallationRepository implements GitHubInstallationRepository {
        final Map<Long, GitHubInstallation> byInstallationId = new HashMap<>();
        final Map<UUID, GitHubInstallation> byOrgId = new HashMap<>();
        final Map<UUID, GitHubInstallation> byWorkspaceId = new HashMap<>();

        @Override
        public GitHubInstallation save(GitHubInstallation installation) {
            byInstallationId.put(installation.getInstallationId(), installation);
            if (installation.getOrganizationId() != null) {
                byOrgId.put(installation.getOrganizationId(), installation);
            }
            if (installation.getWorkspaceId() != null) {
                byWorkspaceId.put(installation.getWorkspaceId(), installation);
            }
            return installation;
        }

        @Override
        public Optional<GitHubInstallation> findByInstallationId(Long installationId) {
            return Optional.ofNullable(byInstallationId.get(installationId));
        }

        @Override
        public Optional<GitHubInstallation> findByOrganizationId(UUID organizationId) {
            return Optional.ofNullable(byOrgId.get(organizationId));
        }

        @Override
        public Optional<GitHubInstallation> findByWorkspaceId(UUID workspaceId) {
            return Optional.ofNullable(byWorkspaceId.get(workspaceId));
        }

        @Override
        public List<GitHubInstallation> findAll() {
            return new ArrayList<>(byInstallationId.values());
        }
    }

    @BeforeEach
    void setUp() {
        gitHubAppConfig = new GitHubAppConfig(9999L, generateRsaKey(), "webhook-secret");
        installationRepository = new InMemoryInstallationRepository();

        repositoryApplicationService = new RepositoryApplicationService(
                repositoryRepository,
                installationRepository,
                pullRequestRepository,
                commitRepository,
                outboxService,
                gitHubAppClient,
                gitHubAppConfig
        );
        repositoryApplicationService.setWorkspaceRepository(workspaceRepository);

        operationsController = new SourceControlOperationsController(
                outboxEventRepository,
                metrics,
                auditService
        );

        // Seed workspaces:
        // WS_A is PERSONAL owned by USER_A
        WorkspaceJpaEntity wsAEntity = WorkspaceJpaEntity.builder()
                .id(WS_A)
                .name("Alice Personal Workspace")
                .type("PERSONAL")
                .ownerUserId(USER_A)
                .organizationId(null)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        lenient().when(workspaceRepository.findById(WS_A)).thenReturn(Optional.of(wsAEntity));

        // WS_B is PERSONAL owned by USER_B
        WorkspaceJpaEntity wsBEntity = WorkspaceJpaEntity.builder()
                .id(WS_B)
                .name("Bob Personal Workspace")
                .type("PERSONAL")
                .ownerUserId(USER_B)
                .organizationId(null)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        lenient().when(workspaceRepository.findById(WS_B)).thenReturn(Optional.of(wsBEntity));
    }

    @Test
    @DisplayName("ADV2-003: User B cannot list personal repositories belonging to User A's workspace")
    void testUserBCannotListUserAPersonalRepositories() {
        // User B attempts to access User A's personal workspace
        assertThatThrownBy(() -> repositoryApplicationService.listRepositories(null, WS_A, USER_B))
                .isInstanceOf(GitHubAccessDeniedException.class)
                .hasMessageContaining("You do not own this personal workspace");

        // User A accessing User A's workspace succeeds
        when(repositoryRepository.findAllByWorkspaceId(WS_A)).thenReturn(List.of(
                Repository.builder()
                        .id(UUID.randomUUID())
                        .workspaceId(WS_A)
                        .scope("PERSONAL")
                        .fullName("alice/secret-repo")
                        .build()
        ));

        List<Repository> repos = repositoryApplicationService.listRepositories(null, WS_A, USER_A);
        assertThat(repos).hasSize(1);
        assertThat(repos.get(0).getFullName()).isEqualTo("alice/secret-repo");
    }

    @Test
    @DisplayName("ADV2-003: User B cannot connect repository into User A's personal workspace")
    void testUserBCannotConnectRepoIntoUserAPersonalWorkspace() {
        assertThatThrownBy(() -> repositoryApplicationService.connectRepository(null, WS_A, "alice/stolen-repo", USER_B))
                .isInstanceOf(GitHubAccessDeniedException.class)
                .hasMessageContaining("You do not own this personal workspace");
    }

    @Test
    @DisplayName("ADV2-003: User B cannot claim installation into User A's personal workspace")
    void testUserBCannotClaimInstallationIntoUserAPersonalWorkspace() {
        assertThatThrownBy(() -> repositoryApplicationService.claimInstallation(null, WS_A, 55555L, USER_B))
                .isInstanceOf(GitHubAccessDeniedException.class)
                .hasMessageContaining("You do not own this personal workspace");
    }

    @Test
    @DisplayName("ADV2-005: Outbox stats endpoint supports X-Workspace-Id for personal workspace")
    void testOutboxStatsSupportsPersonalWorkspace() {
        when(outboxEventRepository.countByWorkspaceIdAndStatus(WS_A, "PENDING")).thenReturn(3L);
        when(outboxEventRepository.countByWorkspaceIdAndStatus(WS_A, "RETRYING")).thenReturn(1L);
        when(outboxEventRepository.countByWorkspaceIdAndStatus(WS_A, "DEAD_LETTER")).thenReturn(2L);
        when(metrics.getRateLimitRemaining()).thenReturn(4900);

        ResponseEntity<Map<String, Object>> response = operationsController.getOutboxStats(
                null, null, WS_A.toString(), "attested"
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> stats = response.getBody();
        assertThat(stats).isNotNull();
        assertThat(stats.get("pending")).isEqualTo(3L);
        assertThat(stats.get("retrying")).isEqualTo(1L);
        assertThat(stats.get("deadLetter")).isEqualTo(2L);
        assertThat(stats.get("totalUnpublished")).isEqualTo(4L);
    }

    @Test
    @DisplayName("ADV2-005: Outbox dead-letter query supports X-Workspace-Id for personal workspace")
    void testDeadLetterQuerySupportsPersonalWorkspace() {
        OutboxEventEntity deadLetterEvent = OutboxEventEntity.builder()
                .id(UUID.randomUUID())
                .workspaceId(WS_A)
                .organizationId(null)
                .status("DEAD_LETTER")
                .deadLetterAt(Instant.now())
                .build();

        when(outboxEventRepository.findByWorkspaceIdAndStatusOrderByDeadLetterAtDesc(
                eq(WS_A), eq("DEAD_LETTER"), any(PageRequest.class)))
                .thenReturn(List.of(deadLetterEvent));

        ResponseEntity<List<OutboxEventEntity>> response = operationsController.getDeadLetterEvents(
                null, null, WS_A.toString(), "attested", 0, 20
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getWorkspaceId()).isEqualTo(WS_A);
    }

    @Test
    @DisplayName("ADV2-005: Replay outbox event succeeds for personal workspace owner and rejects cross-workspace attempt")
    void testReplayPersonalOutboxEventWithIsolation() {
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity deadLetterEvent = OutboxEventEntity.builder()
                .id(eventId)
                .workspaceId(WS_A)
                .organizationId(null)
                .status("DEAD_LETTER")
                .attemptCount(5)
                .deadLetterAt(Instant.now())
                .lastError("Timeout communicating with downstream")
                .build();

        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(deadLetterEvent));

        AuditEventEntity auditEvent = AuditEventEntity.builder()
                .id(UUID.randomUUID())
                .workspaceId(WS_A)
                .eventType("OUTBOX_REPLAY_REQUESTED")
                .build();
        lenient().when(auditService.recordEvent(org.mockito.ArgumentMatchers.nullable(UUID.class), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(auditEvent);

        // Attempt 1: Cross-workspace attack: Attacker provides X-Workspace-Id of WS_B
        ResponseEntity<Map<String, Object>> attackResponse = operationsController.replayOutboxEvent(
                eventId,
                null, null, WS_B.toString(),
                USER_B.toString(), "ROLE_ADMIN", "outbox:replay", "attested"
        );
        assertThat(attackResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(attackResponse.getBody().get("error").toString())
                .contains("Forbidden: outbox event does not belong to your organization or workspace");

        // Attempt 2: Attacker provides an arbitrary Organization-Id
        ResponseEntity<Map<String, Object>> orgAttackResponse = operationsController.replayOutboxEvent(
                eventId,
                UUID.randomUUID().toString(), null, null,
                USER_B.toString(), "ROLE_ADMIN", "outbox:replay", "attested"
        );
        assertThat(orgAttackResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Attempt 3: Legitimate replay for WS_A
        ResponseEntity<Map<String, Object>> successResponse = operationsController.replayOutboxEvent(
                eventId,
                null, null, WS_A.toString(),
                USER_A.toString(), "ROLE_ADMIN", "outbox:replay", "attested"
        );

        assertThat(successResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(successResponse.getBody().get("status")).isEqualTo("REPLAY_SCHEDULED");
        assertThat(deadLetterEvent.getStatus()).isEqualTo("PENDING");
        assertThat(deadLetterEvent.getAttemptCount()).isEqualTo(0);
        assertThat(deadLetterEvent.getDeadLetterAt()).isNull();
        assertThat(deadLetterEvent.getLastError()).isNull();
        verify(outboxEventRepository).save(deadLetterEvent);
    }

    private String generateRsaKey() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            var pair = kpg.generateKeyPair();
            return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
