package com.forgeai.sourcecontrol;

import com.forgeai.sourcecontrol.application.dto.GitHubConnectionResponse;
import com.forgeai.sourcecontrol.application.service.*;
import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.model.InstallationStatus;
import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.domain.model.RepositorySyncStatus;
import com.forgeai.sourcecontrol.domain.repository.CommitRepository;
import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import com.forgeai.sourcecontrol.domain.repository.PullRequestRepository;
import com.forgeai.sourcecontrol.domain.repository.RepositoryRepository;
import com.forgeai.sourcecontrol.infrastructure.github.*;
import com.forgeai.sourcecontrol.infrastructure.identity.IdentityServiceClient;
import com.forgeai.sourcecontrol.infrastructure.metrics.SourceControlMetrics;
import com.forgeai.sourcecontrol.infrastructure.outbox.OutboxService;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.GitHubInstallationStateJpaEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.WorkspaceJpaEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataGitHubInstallationStateRepository;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataWorkspaceRepository;
import com.forgeai.sourcecontrol.infrastructure.security.TenantContext;
import com.forgeai.sourcecontrol.presentation.controller.GitHubConnectionController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Adversarial Security & Lifecycle Test Suite for GitHub App Installation:
 *
 * Test A: User A connects GitHub App -> installation created, state consumed, associated with Workspace A.
 * Test B: User B independently connects -> installation associated with Workspace B, isolated from User A.
 * Test C: User B attempts to hijack User A's installation ID -> 403 Forbidden / Access Denied.
 * Test D: User B attempts to target User A's workspace -> 403 Forbidden / Access Denied.
 * Test E: Direct repository access attack across personal workspaces -> 403 Forbidden / Access Denied.
 * Test F: Callback replay attack -> 403 Forbidden / Access Denied (state already consumed).
 * Test G: Expired callback attack -> 403 Forbidden / Access Denied (state expired).
 * Test H: Forged / non-existent installation ID -> 403 Forbidden / Access Denied.
 */
@ExtendWith(MockitoExtension.class)
public class GitHubInstallationLifecycleSecurityTest {

    @Mock
    private GitHubAppClient gitHubAppClient;

    @Mock
    private IdentityServiceClient identityServiceClient;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private CommitRepository commitRepository;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private SpringDataWorkspaceRepository workspaceRepository;

    @Mock
    private SpringDataGitHubInstallationStateRepository stateRepository;

    @Mock
    private OutboxService outboxService;

    @Mock
    private SourceControlMetrics metrics;

    private GitHubAppConfig gitHubAppConfig;
    private InMemoryInstallationRepository installationRepository;
    private GitHubInstallationStateService stateService;
    private GitHubInstallationDiscoveryService discoveryService;
    private GitHubConnectionService connectionService;
    private RepositoryApplicationService repositoryApplicationService;
    private GitHubConnectionController connectionController;

    private static final UUID USER_A_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_B_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID WS_A_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID WS_B_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final Long INSTALLATION_A_ID = 171260102L;
    private static final Long INSTALLATION_B_ID = 999888777L;

    private static final Long GH_USER_A_ID = 171410247L;
    private static final String GH_USER_A_LOGIN = "rajpagare01";

    private static final Long GH_USER_B_ID = 888123456L;
    private static final String GH_USER_B_LOGIN = "attacker-user";

    private final Map<String, GitHubInstallationStateJpaEntity> stateDb = new HashMap<>();

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
        public Optional<GitHubInstallation> findByAccountId(Long accountId) {
            return byInstallationId.values().stream()
                    .filter(i -> accountId.equals(i.getAccountId()))
                    .findFirst();
        }

        @Override
        public List<GitHubInstallation> findByAccountLogin(String accountLogin) {
            return byInstallationId.values().stream()
                    .filter(i -> accountLogin.equalsIgnoreCase(i.getAccountLogin()))
                    .toList();
        }

        @Override
        public List<GitHubInstallation> findAll() {
            return new ArrayList<>(byInstallationId.values());
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.clear();
        stateDb.clear();

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        String pem = "-----BEGIN RSA PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(kp.getPrivate().getEncoded()) +
                "\n-----END RSA PRIVATE KEY-----";

        gitHubAppConfig = new GitHubAppConfig("5311634", "forgeai-raj-dev", pem, "", "dummy-secret");
        installationRepository = new InMemoryInstallationRepository();

        lenient().when(stateRepository.save(any(GitHubInstallationStateJpaEntity.class))).thenAnswer(inv -> {
            GitHubInstallationStateJpaEntity e = inv.getArgument(0);
            stateDb.put(e.getState(), e);
            return e;
        });
        lenient().when(stateRepository.findByState(anyString())).thenAnswer(inv -> {
            String token = inv.getArgument(0);
            return Optional.ofNullable(stateDb.get(token));
        });

        // Mock repository save in memory
        lenient().when(repositoryRepository.save(any(Repository.class))).thenAnswer(inv -> {
            Repository r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            return r;
        });

        stateService = new GitHubInstallationStateService(stateRepository, metrics);

        discoveryService = new GitHubInstallationDiscoveryService(
                gitHubAppClient,
                gitHubAppConfig,
                installationRepository,
                repositoryRepository,
                workspaceRepository,
                identityServiceClient,
                outboxService,
                null,
                metrics
        );

        InstallationReconciliationService reconciliationService = new InstallationReconciliationService(
                gitHubAppClient,
                installationRepository,
                repositoryRepository,
                workspaceRepository,
                outboxService
        );

        connectionService = new GitHubConnectionService(
                gitHubAppConfig,
                gitHubAppClient,
                installationRepository,
                repositoryRepository,
                workspaceRepository,
                identityServiceClient,
                discoveryService,
                reconciliationService
        );

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

        connectionController = new GitHubConnectionController(
                connectionService,
                discoveryService,
                stateService,
                gitHubAppConfig
        );

        WorkspaceJpaEntity wsA = WorkspaceJpaEntity.builder()
                .id(WS_A_ID)
                .ownerUserId(USER_A_ID)
                .type("PERSONAL")
                .name("rajpagare305's Workspace")
                .build();
        lenient().when(workspaceRepository.findById(WS_A_ID)).thenReturn(Optional.of(wsA));

        WorkspaceJpaEntity wsB = WorkspaceJpaEntity.builder()
                .id(WS_B_ID)
                .ownerUserId(USER_B_ID)
                .type("PERSONAL")
                .name("User B's Workspace")
                .build();
        lenient().when(workspaceRepository.findById(WS_B_ID)).thenReturn(Optional.of(wsB));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Test A: User A Installs GitHub App
    // =========================================================================
    @Test
    @DisplayName("Test A: User A initiates install, state is generated, callback associates installation with Workspace A")
    void testA_UserA_InstallationSuccess() {
        TenantContext.setUserId(USER_A_ID);
        TenantContext.setWorkspaceId(WS_A_ID);
        TenantContext.setWorkspaceScope("PERSONAL");

        ResponseEntity<GitHubConnectionController.InstallationStartResponse> startRes =
                connectionController.startInstallation(new GitHubConnectionController.InstallationStartRequest(WS_A_ID));

        assertThat(startRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        String stateToken = Objects.requireNonNull(startRes.getBody()).state();
        assertThat(stateToken).isNotBlank();
        assertThat(startRes.getBody().installUrl()).contains("state=" + stateToken);

        when(identityServiceClient.getUserGitHubProfile(USER_A_ID)).thenReturn(Optional.of(
                new IdentityServiceClient.UserGitHubProfile(true, GH_USER_A_ID, GH_USER_A_LOGIN, "Raj Pagare", "raj@test.com", null)
        ));

        GitHubInstallationDetails detailsA = new GitHubInstallationDetails(
                INSTALLATION_A_ID, 5311634L, GH_USER_A_ID, GH_USER_A_LOGIN, "User", "all", false
        );
        when(gitHubAppClient.getInstallationDetails(INSTALLATION_A_ID)).thenReturn(detailsA);

        List<GitHubRepositoryMetadata> reposA = List.of(
                new GitHubRepositoryMetadata(101L, INSTALLATION_A_ID, "repo-alpha", "rajpagare01/repo-alpha", false, "main", GH_USER_A_LOGIN)
        );
        when(gitHubAppClient.listInstallationRepositories(INSTALLATION_A_ID)).thenReturn(reposA);

        ResponseEntity<Void> callbackRes = connectionController.handleInstallationCallbackGet(
                INSTALLATION_A_ID, "install", stateToken
        );

        assertThat(callbackRes.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(Objects.requireNonNull(callbackRes.getHeaders().getLocation()).toString()).contains("installed=true");

        Optional<GitHubInstallation> instOpt = installationRepository.findByInstallationId(INSTALLATION_A_ID);
        assertThat(instOpt).isPresent();
        GitHubInstallation inst = instOpt.get();
        assertThat(inst.getWorkspaceId()).isEqualTo(WS_A_ID);
        assertThat(inst.getClaimedByUserId()).isEqualTo(USER_A_ID);
        assertThat(inst.getAccountLogin()).isEqualTo(GH_USER_A_LOGIN);
        assertThat(inst.getStatus()).isEqualTo(InstallationStatus.ACTIVE);

        GitHubInstallationStateJpaEntity consumedState = stateDb.get(stateToken);
        assertThat(consumedState.isConsumed()).isTrue();
    }

    // =========================================================================
    // Test B: User B Independently Installs GitHub App
    // =========================================================================
    @Test
    @DisplayName("Test B: User B independently installs GitHub App into Workspace B with per-user repository isolation")
    void testB_UserB_InstallationSuccess() {
        TenantContext.setUserId(USER_B_ID);
        TenantContext.setWorkspaceId(WS_B_ID);
        TenantContext.setWorkspaceScope("PERSONAL");

        ResponseEntity<GitHubConnectionController.InstallationStartResponse> startRes =
                connectionController.startInstallation(new GitHubConnectionController.InstallationStartRequest(WS_B_ID));
        String stateToken = Objects.requireNonNull(startRes.getBody()).state();

        when(identityServiceClient.getUserGitHubProfile(USER_B_ID)).thenReturn(Optional.of(
                new IdentityServiceClient.UserGitHubProfile(true, GH_USER_B_ID, GH_USER_B_LOGIN, "Attacker User", "attacker@test.com", null)
        ));

        GitHubInstallationDetails detailsB = new GitHubInstallationDetails(
                INSTALLATION_B_ID, 5311634L, GH_USER_B_ID, GH_USER_B_LOGIN, "User", "all", false
        );
        when(gitHubAppClient.getInstallationDetails(INSTALLATION_B_ID)).thenReturn(detailsB);

        List<GitHubRepositoryMetadata> reposB = List.of(
                new GitHubRepositoryMetadata(202L, INSTALLATION_B_ID, "repo-beta", "attacker-user/repo-beta", true, "main", GH_USER_B_LOGIN)
        );
        when(gitHubAppClient.listInstallationRepositories(INSTALLATION_B_ID)).thenReturn(reposB);

        ResponseEntity<GitHubConnectionResponse> callbackRes = connectionController.handleInstallationCallbackPost(
                new GitHubConnectionController.InstallationCallbackRequest(INSTALLATION_B_ID, stateToken, WS_B_ID)
        );

        assertThat(callbackRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        Optional<GitHubInstallation> instOpt = installationRepository.findByInstallationId(INSTALLATION_B_ID);
        assertThat(instOpt).isPresent();
        assertThat(instOpt.get().getWorkspaceId()).isEqualTo(WS_B_ID);
        assertThat(instOpt.get().getClaimedByUserId()).isEqualTo(USER_B_ID);
        assertThat(instOpt.get().getAccountLogin()).isEqualTo(GH_USER_B_LOGIN);
    }

    // =========================================================================
    // Test C: User B Attempts to Hijack User A's Installation ID (403 Forbidden)
    // =========================================================================
    @Test
    @DisplayName("Test C: User B attempts to hijack User A's installation ID -> 403 Forbidden")
    void testC_UserB_AttemptsToHijackInstallationA_Forbidden() {
        GitHubInstallation existingA = GitHubInstallation.builder()
                .installationId(INSTALLATION_A_ID)
                .workspaceId(WS_A_ID)
                .claimedByUserId(USER_A_ID)
                .accountId(GH_USER_A_ID)
                .accountLogin(GH_USER_A_LOGIN)
                .scope("PERSONAL")
                .status(InstallationStatus.ACTIVE)
                .build();
        installationRepository.save(existingA);

        when(identityServiceClient.getUserGitHubProfile(USER_B_ID)).thenReturn(Optional.of(
                new IdentityServiceClient.UserGitHubProfile(true, GH_USER_B_ID, GH_USER_B_LOGIN, "Attacker User", "b@test.com", null)
        ));

        GitHubInstallationDetails detailsA = new GitHubInstallationDetails(
                INSTALLATION_A_ID, 5311634L, GH_USER_A_ID, GH_USER_A_LOGIN, "User", "all", false
        );
        when(gitHubAppClient.getInstallationDetails(INSTALLATION_A_ID)).thenReturn(detailsA);

        assertThatThrownBy(() -> discoveryService.validateAndAssociate(USER_B_ID, WS_B_ID, null, INSTALLATION_A_ID))
                .isInstanceOf(GitHubAccessDeniedException.class)
                .hasMessageContaining("does not match your authenticated GitHub account");
    }

    // =========================================================================
    // Test D: User B Attempts to Associate into User A's Workspace (403 Forbidden)
    // =========================================================================
    @Test
    @DisplayName("Test D: User B attempts to target User A's Workspace -> 403 Forbidden")
    void testD_UserB_AttemptsToTargetWorkspaceA_Forbidden() {
        assertThatThrownBy(() -> discoveryService.autoOnboard(USER_B_ID, WS_A_ID, null, INSTALLATION_B_ID))
                .isInstanceOf(GitHubAccessDeniedException.class)
                .hasMessageContaining("Access denied: You do not own this personal workspace");
    }

    // =========================================================================
    // Test E: Direct Repository Access Attack (Cross-Workspace Isolation)
    // =========================================================================
    @Test
    @DisplayName("Test E: User B attempts direct repo access to User A's repository -> 403 Forbidden")
    void testE_UserB_DirectRepoAccessAttack_Forbidden() {
        UUID repoAId = UUID.randomUUID();
        Repository repoA = Repository.builder()
                .id(repoAId)
                .workspaceId(WS_A_ID)
                .scope("PERSONAL")
                .githubRepoId(99901L)
                .fullName("rajpagare01/private-core")
                .syncStatus(RepositorySyncStatus.SYNCED)
                .build();

        lenient().when(repositoryRepository.findByIdAndWorkspaceId(repoAId, WS_A_ID)).thenReturn(Optional.of(repoA));
        lenient().when(repositoryRepository.findByIdAndWorkspaceId(repoAId, WS_B_ID)).thenReturn(Optional.empty());

        TenantContext.setUserId(USER_B_ID);
        TenantContext.setWorkspaceId(WS_B_ID);
        TenantContext.setWorkspaceScope("PERSONAL");

        // Attack vector 1: User B attempts to query Repo A by spoofing User A's workspace context -> 403 Forbidden
        assertThatThrownBy(() -> repositoryApplicationService.getRepository(null, WS_A_ID, repoAId, USER_B_ID))
                .isInstanceOf(GitHubAccessDeniedException.class)
                .hasMessageContaining("Access denied: You do not own this personal workspace");

        // Attack vector 2: User B attempts to access Repo A within User B's own workspace context -> ResourceNotFound (no cross-workspace leakage)
        assertThatThrownBy(() -> repositoryApplicationService.getRepository(null, WS_B_ID, repoAId, USER_B_ID))
                .isInstanceOf(com.forgeai.sourcecontrol.domain.exception.ResourceNotFoundException.class)
                .hasMessageContaining("Repository not found");
    }

    // =========================================================================
    // Test F: Callback Replay Attack Rejected
    // =========================================================================
    @Test
    @DisplayName("Test F: Callback replay attack with consumed state token is rejected")
    void testF_CallbackReplayAttack_Rejected() {
        String stateToken = stateService.createInstallationState(USER_A_ID, WS_A_ID, null, "PERSONAL");

        GitHubInstallationStateJpaEntity firstConsume = stateService.validateAndConsumeState(stateToken, USER_A_ID, WS_A_ID);
        assertThat(firstConsume.isConsumed()).isTrue();

        assertThatThrownBy(() -> stateService.validateAndConsumeState(stateToken, USER_A_ID, WS_A_ID))
                .isInstanceOf(GitHubAccessDeniedException.class)
                .hasMessageContaining("already been consumed (replay rejected)");
    }

    // =========================================================================
    // Test G: Expired Callback Attack Rejected
    // =========================================================================
    @Test
    @DisplayName("Test G: Callback with expired state token is rejected")
    void testG_ExpiredCallbackAttack_Rejected() {
        Instant past = Instant.now().minus(Duration.ofHours(2));
        GitHubInstallationStateJpaEntity expiredEntity = GitHubInstallationStateJpaEntity.builder()
                .id(UUID.randomUUID())
                .state("expired-state-token-12345")
                .userId(USER_A_ID)
                .workspaceId(WS_A_ID)
                .scope("PERSONAL")
                .createdAt(past.minus(Duration.ofMinutes(15)))
                .expiresAt(past)
                .build();

        stateDb.put(expiredEntity.getState(), expiredEntity);

        assertThatThrownBy(() -> stateService.validateAndConsumeState("expired-state-token-12345", USER_A_ID, WS_A_ID))
                .isInstanceOf(GitHubAccessDeniedException.class)
                .hasMessageContaining("state token has expired");
    }

    // =========================================================================
    // Test H: Forged / Non-Existent Installation ID Rejected
    // =========================================================================
    @Test
    @DisplayName("Test H: Forged installation ID that cannot be resolved on GitHub is rejected")
    void testH_ForgedInstallationId_Rejected() {
        long forgedInstallationId = 12345678999999L;

        when(gitHubAppClient.getInstallationDetails(forgedInstallationId)).thenReturn(null);

        assertThatThrownBy(() -> discoveryService.validateAndAssociate(USER_A_ID, WS_A_ID, null, forgedInstallationId))
                .isInstanceOf(GitHubAccessDeniedException.class)
                .hasMessageContaining("could not be resolved on GitHub");
    }
}
