package com.forgeai.sourcecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.sourcecontrol.application.dto.InstallationDiscoveryResponse;
import com.forgeai.sourcecontrol.application.dto.ReconciliationReport;
import com.forgeai.sourcecontrol.application.handler.InstallationEventHandler;
import com.forgeai.sourcecontrol.application.service.GitHubInstallationDiscoveryService;
import com.forgeai.sourcecontrol.application.service.InstallationReconciliationService;
import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.model.InstallationStatus;
import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.domain.model.RepositorySyncStatus;
import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import com.forgeai.sourcecontrol.domain.repository.RepositoryRepository;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAccessDeniedException;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubInstallationDetails;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubRepositoryMetadata;
import com.forgeai.sourcecontrol.infrastructure.identity.IdentityServiceClient;
import com.forgeai.sourcecontrol.infrastructure.outbox.OutboxService;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.WorkspaceJpaEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataWorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AutomaticInstallationDiscoveryAndOnboardingTest {

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private OutboxService outboxService;

    @Mock
    private GitHubAppClient gitHubAppClient;

    @Mock
    private IdentityServiceClient identityServiceClient;

    @Mock
    private SpringDataWorkspaceRepository workspaceRepository;

    private GitHubAppConfig gitHubAppConfig;
    private InMemoryInstallationRepository installationRepository;
    private GitHubInstallationDiscoveryService discoveryService;
    private InstallationReconciliationService reconciliationService;
    private InstallationEventHandler installationEventHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
    void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var keyPair = kpg.generateKeyPair();
        String fakePem = "-----BEGIN RSA PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyPair.getPrivate().getEncoded()) +
                "\n-----END RSA PRIVATE KEY-----";

        gitHubAppConfig = new GitHubAppConfig(9999L, fakePem, "test-webhook-secret");
        installationRepository = new InMemoryInstallationRepository();

        WorkspaceJpaEntity wsA = WorkspaceJpaEntity.builder()
                .id(WS_A)
                .type("PERSONAL")
                .ownerUserId(USER_A)
                .name("Alice Personal")
                .build();
        lenient().when(workspaceRepository.findById(WS_A)).thenReturn(Optional.of(wsA));

        WorkspaceJpaEntity wsB = WorkspaceJpaEntity.builder()
                .id(WS_B)
                .type("PERSONAL")
                .ownerUserId(USER_B)
                .name("Bob Personal")
                .build();
        lenient().when(workspaceRepository.findById(WS_B)).thenReturn(Optional.of(wsB));

        discoveryService = new GitHubInstallationDiscoveryService(
                gitHubAppClient,
                gitHubAppConfig,
                installationRepository,
                repositoryRepository,
                workspaceRepository,
                identityServiceClient,
                outboxService,
                null
        );

        reconciliationService = new InstallationReconciliationService(
                gitHubAppClient,
                installationRepository,
                repositoryRepository,
                workspaceRepository,
                outboxService
        );

        installationEventHandler = new InstallationEventHandler(
                installationRepository,
                repositoryRepository,
                outboxService
        );
    }

    @Test
    @DisplayName("Discovery: Automatically detects user installation without manual ID entry")
    void testDiscoverInstallations_AutomaticallyDiscovered() {
        // Given User A is linked to GitHub user 'alice-dev' (id: 12345)
        when(identityServiceClient.getUserGitHubProfile(USER_A)).thenReturn(Optional.of(
                new IdentityServiceClient.UserGitHubProfile(true, 12345L, "alice-dev", "Alice", "alice@example.com", null)
        ));

        // GitHub App API detects an installation for alice-dev
        when(gitHubAppClient.findUserInstallation("alice-dev")).thenReturn(Optional.of(
                new GitHubInstallationDetails(889900L, 9999L, 12345L, "alice-dev", "User", "ALL", false)
        ));

        // When discovery is invoked for User A's workspace
        InstallationDiscoveryResponse response = discoveryService.discoverInstallations(USER_A, WS_A, null);

        // Then installation is discovered without manual input
        assertThat(response).isNotNull();
        assertThat(response.gitHubConnected()).isTrue();
        assertThat(response.userGitHubLogin()).isEqualTo("alice-dev");
        assertThat(response.candidates()).hasSize(1);
        assertThat(response.candidates().get(0).installationId()).isEqualTo(889900L);
        assertThat(response.candidates().get(0).accountLogin()).isEqualTo("alice-dev");
        assertThat(response.candidates().get(0).status()).isEqualTo(InstallationStatus.CLAIMABLE);
    }

    @Test
    @DisplayName("Security: Setup callback rejects spoofed installationId not belonging to user")
    void testSetupCallback_RejectsSpoofedInstallationId() {
        // Given User A is linked to GitHub account 'alice-dev' (id: 12345)
        when(identityServiceClient.getUserGitHubProfile(USER_A)).thenReturn(Optional.of(
                new IdentityServiceClient.UserGitHubProfile(true, 12345L, "alice-dev", "Alice", "alice@example.com", null)
        ));

        // Attacker provides spoofed installation 999111 belonging to victim 'bob-corp' (id: 67890)
        when(gitHubAppClient.getInstallationDetails(999111L)).thenReturn(
                new GitHubInstallationDetails(999111L, 9999L, 67890L, "bob-corp", "User", "ALL", false)
        );

        // When/Then: setup callback must be strictly rejected
        assertThatThrownBy(() -> discoveryService.handleSetupCallback(USER_A, WS_A, null, 999111L))
                .isInstanceOf(GitHubAccessDeniedException.class)
                .hasMessageContaining("does not match your authenticated GitHub account");

        // Installation must NOT be associated
        assertThat(installationRepository.findByInstallationId(999111L)).isEmpty();
    }

    @Test
    @DisplayName("Onboarding: Valid installation associates personal workspace and discovers repos")
    void testSetupCallback_Success_AssociatesWorkspaceAndStagesRepositories() {
        // Given User A owns GitHub account 'alice-dev' (id: 12345)
        when(identityServiceClient.getUserGitHubProfile(USER_A)).thenReturn(Optional.of(
                new IdentityServiceClient.UserGitHubProfile(true, 12345L, "alice-dev", "Alice", "alice@example.com", null)
        ));

        when(gitHubAppClient.getInstallationDetails(889900L)).thenReturn(
                new GitHubInstallationDetails(889900L, 9999L, 12345L, "alice-dev", "User", "SELECTED", false)
        );

        when(gitHubAppClient.listInstallationRepositories(889900L)).thenReturn(List.of(
                new GitHubRepositoryMetadata(101L, 889900L, "my-repo", "alice-dev/my-repo", false, "main", "alice-dev")
        ));

        when(repositoryRepository.save(any(Repository.class))).thenAnswer(inv -> {
            Repository r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        // When setup callback is processed
        GitHubInstallation claimed = discoveryService.handleSetupCallback(USER_A, WS_A, null, 889900L);

        // Then installation is active and associated with User A's workspace
        assertThat(claimed).isNotNull();
        assertThat(claimed.getStatus()).isEqualTo(InstallationStatus.ACTIVE);
        assertThat(claimed.getWorkspaceId()).isEqualTo(WS_A);
        assertThat(claimed.getScope()).isEqualTo("PERSONAL");
        assertThat(claimed.getOrganizationId()).isNull();
        assertThat(claimed.getClaimedByUserId()).isEqualTo(USER_A);

        // Repository was created and outbox event published
        verify(repositoryRepository).save(any(Repository.class));
        verify(outboxService).publish(any(), any(), eq("Repository"), any());
    }

    @Test
    @DisplayName("Auto-Onboard: End-to-end zero-click onboarding when installation exists")
    void testAutoOnboard_ZeroClickFlow() {
        // Given User A is connected to GitHub
        when(identityServiceClient.getUserGitHubProfile(USER_A)).thenReturn(Optional.of(
                new IdentityServiceClient.UserGitHubProfile(true, 12345L, "alice-dev", "Alice", "alice@example.com", null)
        ));

        when(gitHubAppClient.findUserInstallation("alice-dev")).thenReturn(Optional.of(
                new GitHubInstallationDetails(889900L, 9999L, 12345L, "alice-dev", "User", "ALL", false)
        ));

        when(gitHubAppClient.getInstallationDetails(889900L)).thenReturn(
                new GitHubInstallationDetails(889900L, 9999L, 12345L, "alice-dev", "User", "ALL", false)
        );

        when(gitHubAppClient.listInstallationRepositories(889900L)).thenReturn(Collections.emptyList());

        // When autoOnboard is invoked with NO installation ID
        GitHubInstallation result = discoveryService.autoOnboard(USER_A, WS_A, null, null);

        // Then discovery automatically resolved installation 889900L and activated it
        assertThat(result.getInstallationId()).isEqualTo(889900L);
        assertThat(result.getWorkspaceId()).isEqualTo(WS_A);
        assertThat(result.getStatus()).isEqualTo(InstallationStatus.ACTIVE);
    }

    @Test
    @DisplayName("Multi-Tenant Isolation: User A cannot claim User B's installation")
    void testMultiTenantIsolation_UserACannotClaimUserBInstallation() {
        // Given installation 777666 is already claimed by User B
        GitHubInstallation bobInst = GitHubInstallation.builder()
                .installationId(777666L)
                .workspaceId(WS_B)
                .scope("PERSONAL")
                .accountLogin("bob-dev")
                .accountId(99999L)
                .claimedByUserId(USER_B)
                .status(InstallationStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        installationRepository.save(bobInst);

        when(gitHubAppClient.getInstallationDetails(777666L)).thenReturn(
                new GitHubInstallationDetails(777666L, 9999L, 99999L, "bob-dev", "User", "ALL", false)
        );

        // When User A attempts to claim User B's installation
        assertThatThrownBy(() -> discoveryService.autoOnboard(USER_A, WS_A, null, 777666L))
                .isInstanceOf(GitHubAccessDeniedException.class);
    }

    @Test
    @DisplayName("Webhook Lifecycle: Repository access removal marks repo INACCESSIBLE without deleting history")
    void testWebhook_RepositoriesRemoved_MarksInaccessible() throws Exception {
        // Given an existing repository in DB
        // Given active installation and existing repository in DB
        GitHubInstallation inst = GitHubInstallation.builder()
                .installationId(889900L)
                .workspaceId(WS_A)
                .scope("PERSONAL")
                .accountLogin("alice-dev")
                .status(InstallationStatus.ACTIVE)
                .build();
        installationRepository.save(inst);

        Repository repo = Repository.builder()
                .id(UUID.randomUUID())
                .workspaceId(WS_A)
                .scope("PERSONAL")
                .installationId(889900L)
                .githubRepoId(9999L)
                .name("secret-repo")
                .fullName("alice-dev/secret-repo")
                .syncStatus(RepositorySyncStatus.SYNCED)
                .build();

        when(repositoryRepository.findByGithubRepoId(9999L)).thenReturn(Optional.of(repo));

        String webhookJson = """
        {
            "action": "removed",
            "installation": {
                "id": 889900,
                "account": { "login": "alice-dev", "type": "User" }
            },
            "repositories_removed": [
                { "id": 9999, "name": "secret-repo", "full_name": "alice-dev/secret-repo" }
            ]
        }
        """;

        // When webhook is processed
        installationEventHandler.handle(objectMapper.readTree(webhookJson), "delivery-123");

        // Then repository is marked INACCESSIBLE
        assertThat(repo.getSyncStatus()).isEqualTo(RepositorySyncStatus.INACCESSIBLE);
        verify(repositoryRepository).save(repo);
    }

    @Test
    @DisplayName("Reconciliation: Idempotently syncs added and removed repositories")
    void testReconciliation_IdempotentDiff() {
        // Given active installation in DB
        GitHubInstallation inst = GitHubInstallation.builder()
                .installationId(889900L)
                .workspaceId(WS_A)
                .scope("PERSONAL")
                .accountLogin("alice-dev")
                .status(InstallationStatus.ACTIVE)
                .build();
        installationRepository.save(inst);

        // DB currently has repo1 (which was removed on GitHub)
        Repository repo1 = Repository.builder()
                .id(UUID.randomUUID())
                .workspaceId(WS_A)
                .scope("PERSONAL")
                .installationId(889900L)
                .githubRepoId(111L)
                .name("old-repo")
                .fullName("alice-dev/old-repo")
                .syncStatus(RepositorySyncStatus.SYNCED)
                .build();

        when(repositoryRepository.findAllByInstallationId(889900L)).thenReturn(List.of(repo1));

        // GitHub currently provides repo2 (new repo added)
        when(gitHubAppClient.getInstallationDetails(889900L)).thenReturn(
                new GitHubInstallationDetails(889900L, 9999L, 12345L, "alice-dev", "User", "SELECTED", false)
        );
        when(gitHubAppClient.listInstallationRepositories(889900L)).thenReturn(List.of(
                new GitHubRepositoryMetadata(222L, 889900L, "new-repo", "alice-dev/new-repo", false, "main", "alice-dev")
        ));

        when(repositoryRepository.save(any(Repository.class))).thenAnswer(inv -> {
            Repository r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });

        // When reconciliation runs
        ReconciliationReport report = reconciliationService.reconcileInstallation(889900L, WS_A, null, USER_A);

        // Then
        assertThat(report.totalAccessible()).isEqualTo(1);
        assertThat(report.newlyAdded()).isEqualTo(1);
        assertThat(report.removedOrInaccessible()).isEqualTo(1);
        assertThat(repo1.getSyncStatus()).isEqualTo(RepositorySyncStatus.INACCESSIBLE);
    }
}
