package com.forgeai.sourcecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.sourcecontrol.application.handler.PullRequestEventHandler;
import com.forgeai.sourcecontrol.application.handler.PushEventHandler;
import com.forgeai.sourcecontrol.application.service.RepositoryApplicationService;
import com.forgeai.sourcecontrol.domain.event.CommitCreatedEvent;
import com.forgeai.sourcecontrol.domain.event.PullRequestOpenedEvent;
import com.forgeai.sourcecontrol.domain.event.RepositoryConnectedEvent;
import com.forgeai.sourcecontrol.domain.exception.DuplicateResourceException;
import com.forgeai.sourcecontrol.domain.exception.ResourceNotFoundException;
import com.forgeai.sourcecontrol.domain.model.*;
import com.forgeai.sourcecontrol.domain.repository.CommitRepository;
import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import com.forgeai.sourcecontrol.domain.repository.PullRequestRepository;
import com.forgeai.sourcecontrol.domain.repository.RepositoryRepository;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAccessDeniedException;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubRepositoryMetadata;
import com.forgeai.sourcecontrol.infrastructure.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SourceControlWorkspaceSecurityE2ETest {

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

    private GitHubAppConfig gitHubAppConfig;
    private InMemoryInstallationRepository installationRepository;
    private RepositoryApplicationService repositoryApplicationService;
    private PushEventHandler pushEventHandler;
    private PullRequestEventHandler pullRequestEventHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final UUID QUARANTINE_ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

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

        pushEventHandler = new PushEventHandler(
                repositoryRepository,
                installationRepository,
                commitRepository,
                outboxService
        );

        pullRequestEventHandler = new PullRequestEventHandler(
                repositoryRepository,
                installationRepository,
                pullRequestRepository,
                outboxService
        );
    }

    @Test
    @DisplayName("E2E-WS-02: Personal Repository -> Push Event -> Commit & Outbox Partitioning without Quarantine")
    void testPersonalPushEventIngestionAndOutboxPartitioning() throws Exception {
        UUID personalWsId = UUID.randomUUID();
        long personalInstId = 123456L;

        // Seed personal installation
        installationRepository.save(GitHubInstallation.builder()
                .installationId(personalInstId)
                .scope("PERSONAL")
                .organizationId(null)
                .workspaceId(personalWsId)
                .accountLogin("alice-dev")
                .accountType("User")
                .status(InstallationStatus.ACTIVE)
                .build());

        when(repositoryRepository.findByGithubRepoId(991122L)).thenReturn(Optional.empty());
        when(repositoryRepository.save(any(Repository.class))).thenAnswer(inv -> {
            Repository r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            return r;
        });
        when(commitRepository.save(any(Commit.class))).thenAnswer(inv -> {
            Commit c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId(UUID.randomUUID());
            }
            return c;
        });

        String pushPayload = """
            {
                "repository": {
                    "id": 991122,
                    "name": "personal-ai-tool",
                    "full_name": "alice-dev/personal-ai-tool",
                    "default_branch": "main",
                    "private": true
                },
                "installation": {
                    "id": %d
                },
                "commits": [
                    {
                        "id": "abc123commit456",
                        "message": "Add initial ML pipeline",
                        "author": { "name": "Alice", "email": "alice@example.com" },
                        "timestamp": "2026-10-22T10:00:00Z"
                    }
                ]
            }
            """.formatted(personalInstId);

        pushEventHandler.handle(objectMapper.readTree(pushPayload), "deliv-push-personal-1");

        // 1. Verify Repository is saved in PERSONAL scope with personal workspaceId and NULL organizationId
        ArgumentCaptor<Repository> repoCaptor = ArgumentCaptor.forClass(Repository.class);
        verify(repositoryRepository).save(repoCaptor.capture());
        Repository savedRepo = repoCaptor.getValue();

        assertThat(savedRepo.getScope()).isEqualTo("PERSONAL");
        assertThat(savedRepo.getWorkspaceId()).isEqualTo(personalWsId);
        assertThat(savedRepo.getOrganizationId()).isNull();
        assertThat(savedRepo.getOrganizationId()).isNotEqualTo(QUARANTINE_ORG_ID);

        // 2. Verify Commit is saved with personal workspaceId
        ArgumentCaptor<Commit> commitCaptor = ArgumentCaptor.forClass(Commit.class);
        verify(commitRepository).save(commitCaptor.capture());
        Commit savedCommit = commitCaptor.getValue();

        assertThat(savedCommit.getWorkspaceId()).isEqualTo(personalWsId);
        assertThat(savedCommit.getSha()).isEqualTo("abc123commit456");

        // 3. Verify Outbox event published carries personal scope and personal workspaceId
        ArgumentCaptor<CommitCreatedEvent> eventCaptor = ArgumentCaptor.forClass(CommitCreatedEvent.class);
        verify(outboxService).publish(eventCaptor.capture(), any(), eq("Commit"), any());
        CommitCreatedEvent outboxEvent = eventCaptor.getValue();

        assertThat(outboxEvent.getScope()).isEqualTo("PERSONAL");
        assertThat(outboxEvent.getWorkspaceId()).isEqualTo(personalWsId);
        assertThat(outboxEvent.getOrganizationId()).isNull();
    }

    @Test
    @DisplayName("E2E-WS-03: Personal vs Organization Claiming Isolation (Cross-scope claims strictly denied)")
    void testPersonalVsOrganizationClaimingIsolation() {
        long instId = 778899L;
        UUID personalWsA = UUID.randomUUID();
        UUID personalWsB = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();

        // 1. Installation initially exists in quarantine
        installationRepository.save(GitHubInstallation.builder()
                .installationId(instId)
                .organizationId(QUARANTINE_ORG_ID)
                .accountLogin("alice-corp")
                .accountType("User")
                .status(InstallationStatus.SUSPENDED)
                .build());

        when(gitHubAppClient.getInstallationAccountLogin(instId)).thenReturn("alice-corp");

        // Alice claims it for personal workspace A
        GitHubInstallation claimed = repositoryApplicationService.claimInstallation(null, personalWsA, instId);
        assertThat(claimed.getScope()).isEqualTo("PERSONAL");
        assertThat(claimed.getWorkspaceId()).isEqualTo(personalWsA);
        assertThat(claimed.getOrganizationId()).isNull();
        assertThat(claimed.getStatus()).isEqualTo(InstallationStatus.ACTIVE);

        // 2. Organization B attempts to claim Alice's personal installation -> DENIED
        assertThatThrownBy(() -> repositoryApplicationService.claimInstallation(orgB, null, instId))
                .isInstanceOf(GitHubAccessDeniedException.class)
                .hasMessageContaining("already claimed by another organization or workspace");

        // 3. User B attempts to claim Alice's personal installation for personal workspace B -> DENIED
        assertThatThrownBy(() -> repositoryApplicationService.claimInstallation(null, personalWsB, instId))
                .isInstanceOf(GitHubAccessDeniedException.class)
                .hasMessageContaining("already claimed by another organization or workspace");
    }

    @Test
    @DisplayName("E2E-WS-09: Scope-Swapping Repository Hijacking Attack is Strictly Prevented")
    void testScopeSwappingAttackPrevented() {
        UUID personalWsId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();
        long ghRepoId = 777123L;

        // Alice's personal installation
        installationRepository.save(GitHubInstallation.builder()
                .installationId(1001L)
                .scope("PERSONAL")
                .organizationId(null)
                .workspaceId(personalWsId)
                .accountLogin("alice")
                .status(InstallationStatus.ACTIVE)
                .build());

        // Attacker org's installation
        installationRepository.save(GitHubInstallation.builder()
                .installationId(2002L)
                .scope("ORGANIZATION")
                .organizationId(orgId)
                .workspaceId(orgId)
                .accountLogin("evil-corp")
                .status(InstallationStatus.ACTIVE)
                .build());

        Repository personalRepo = Repository.builder()
                .id(repoId)
                .scope("PERSONAL")
                .workspaceId(personalWsId)
                .organizationId(null)
                .githubRepoId(ghRepoId)
                .fullName("alice/secret-agent")
                .defaultBranch("main")
                .build();

        when(gitHubAppClient.getVerifiedRepository(any(), eq("alice/secret-agent")))
                .thenReturn(new GitHubRepositoryMetadata(ghRepoId, 2002L, "secret-agent", "alice/secret-agent", true, "main", "alice"));
        when(repositoryRepository.findByGithubRepoId(ghRepoId)).thenReturn(Optional.of(personalRepo));

        // Attacker org attempts to connect alice's personal repo -> DENIED
        assertThatThrownBy(() -> repositoryApplicationService.connectRepository(orgId, null, "alice/secret-agent"))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("already connected to another organization or workspace");

        // Attacker from another personal workspace attempts to connect alice's personal repo -> DENIED
        UUID attackerWs = UUID.randomUUID();
        installationRepository.save(GitHubInstallation.builder()
                .installationId(3003L)
                .scope("PERSONAL")
                .organizationId(null)
                .workspaceId(attackerWs)
                .accountLogin("bob")
                .status(InstallationStatus.ACTIVE)
                .build());

        assertThatThrownBy(() -> repositoryApplicationService.connectRepository(null, attackerWs, "alice/secret-agent"))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("already connected to another organization or workspace");
    }

    @Test
    @DisplayName("E2E-WS-10: Pull Request for Personal Installation Partitions to Personal Workspace (No Quarantine)")
    void testPullRequestRespectsPersonalScope() throws Exception {
        UUID personalWsId = UUID.randomUUID();
        long personalInstId = 445566L;
        UUID repoId = UUID.randomUUID();

        installationRepository.save(GitHubInstallation.builder()
                .installationId(personalInstId)
                .scope("PERSONAL")
                .organizationId(null)
                .workspaceId(personalWsId)
                .accountLogin("alice-dev")
                .accountType("User")
                .status(InstallationStatus.ACTIVE)
                .build());

        Repository personalRepo = Repository.builder()
                .id(repoId)
                .scope("PERSONAL")
                .workspaceId(personalWsId)
                .organizationId(null)
                .githubRepoId(887766L)
                .fullName("alice-dev/ai-core")
                .defaultBranch("main")
                .build();

        when(repositoryRepository.findByGithubRepoId(887766L)).thenReturn(Optional.of(personalRepo));
        when(pullRequestRepository.findByRepositoryIdAndNumber(repoId, 42)).thenReturn(Optional.empty());
        when(pullRequestRepository.save(any(PullRequest.class))).thenAnswer(inv -> {
            PullRequest pr = inv.getArgument(0);
            if (pr.getId() == null) {
                pr.setId(UUID.randomUUID());
            }
            return pr;
        });

        String prPayload = """
            {
                "action": "opened",
                "installation": { "id": %d },
                "repository": {
                    "id": 887766,
                    "name": "ai-core",
                    "full_name": "alice-dev/ai-core",
                    "default_branch": "main",
                    "private": true
                },
                "pull_request": {
                    "id": 10101,
                    "number": 42,
                    "title": "Feature: Deep neural verification",
                    "body": "Implements invariant verification",
                    "state": "open",
                    "head": { "ref": "feature/neural-verify", "sha": "headsha123" },
                    "base": { "ref": "main", "sha": "basesha456" },
                    "user": { "login": "alice-dev" },
                    "created_at": "2026-10-22T10:00:00Z",
                    "updated_at": "2026-10-22T10:00:00Z"
                }
            }
            """.formatted(personalInstId);

        pullRequestEventHandler.handle(objectMapper.readTree(prPayload), "deliv-pr-personal-1");

        // 1. Verify PR is saved with personal workspaceId and NULL organizationId
        ArgumentCaptor<PullRequest> prCaptor = ArgumentCaptor.forClass(PullRequest.class);
        verify(pullRequestRepository).save(prCaptor.capture());
        PullRequest savedPr = prCaptor.getValue();

        assertThat(savedPr.getWorkspaceId()).isEqualTo(personalWsId);
        assertThat(savedPr.getOrganizationId()).isNull();

        // 2. Verify outbox event carries personal scope and workspaceId
        ArgumentCaptor<PullRequestOpenedEvent> eventCaptor = ArgumentCaptor.forClass(PullRequestOpenedEvent.class);
        verify(outboxService).publish(eventCaptor.capture(), any(), eq("PullRequest"), any());
        PullRequestOpenedEvent prEvent = eventCaptor.getValue();

        assertThat(prEvent.getScope()).isEqualTo("PERSONAL");
        assertThat(prEvent.getWorkspaceId()).isEqualTo(personalWsId);
        assertThat(prEvent.getOrganizationId()).isNull();
    }

    @Test
    @DisplayName("E2E-WS-14: Cross-Tenant Workspace Bypass Attempt is Strictly Rejected Across Service Operations")
    void testCrossTenantWorkspaceBypassRejected() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();

        // Attempting to bypass org boundaries by specifying a different workspaceId
        assertThatThrownBy(() -> repositoryApplicationService.getRepository(orgA, orgB, repoId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match workspace context");

        assertThatThrownBy(() -> repositoryApplicationService.listRepositories(orgA, orgB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match workspace context");

        assertThatThrownBy(() -> repositoryApplicationService.getClaimedInstallation(orgA, orgB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match workspace context");

        assertThatThrownBy(() -> repositoryApplicationService.listPullRequests(orgA, orgB, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match workspace context");

        assertThatThrownBy(() -> repositoryApplicationService.getPullRequest(orgA, orgB, repoId, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match workspace context");

        assertThatThrownBy(() -> repositoryApplicationService.listCommits(orgA, orgB, repoId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match workspace context");

        assertThatThrownBy(() -> repositoryApplicationService.listBranches(orgA, orgB, repoId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match workspace context");

        assertThatThrownBy(() -> repositoryApplicationService.syncCommits(orgA, orgB, repoId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match workspace context");

        assertThatThrownBy(() -> repositoryApplicationService.syncPullRequests(orgA, orgB, repoId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match workspace context");

        assertThatThrownBy(() -> repositoryApplicationService.getSyncStatus(orgA, orgB, repoId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match workspace context");

        assertThatThrownBy(() -> repositoryApplicationService.reconcileRepository(orgA, orgB, repoId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match workspace context");
    }

    @Test
    @DisplayName("E2E-WS-15: Personal Workspace Caller Cannot Access Organization Repositories")
    void testPersonalWorkspaceCannotAccessOrganizationRepo() {
        UUID orgId = UUID.randomUUID();
        UUID orgRepoId = UUID.randomUUID();

        Repository orgRepo = Repository.builder()
                .id(orgRepoId)
                .scope("ORGANIZATION")
                .organizationId(orgId)
                .workspaceId(orgId)
                .githubRepoId(999888L)
                .fullName("corp/internal-tool")
                .defaultBranch("main")
                .build();

        when(repositoryRepository.findByIdAndWorkspaceId(orgRepoId, orgId)).thenReturn(Optional.of(orgRepo));

        // Personal caller (organizationId == null) attempting to access org repo via workspaceId -> REJECTED
        assertThatThrownBy(() -> repositoryApplicationService.getRepository(null, orgId, orgRepoId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("E2E-WS-16: Personal Workspace Pull Request and Changeset Retrieval Succeeds")
    void testPersonalWorkspacePullRequestAndChangesetRetrieval() {
        UUID personalWsId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();
        UUID prId = UUID.randomUUID();

        Repository personalRepo = Repository.builder()
                .id(repoId)
                .scope("PERSONAL")
                .organizationId(null)
                .workspaceId(personalWsId)
                .githubRepoId(112233L)
                .fullName("alice/my-repo")
                .defaultBranch("main")
                .build();

        PullRequest pr = PullRequest.builder()
                .id(prId)
                .repositoryId(repoId)
                .organizationId(null)
                .workspaceId(personalWsId)
                .number(7)
                .title("Add workspace support")
                .sourceBranch("feat/ws")
                .targetBranch("main")
                .status(PullRequestStatus.OPEN)
                .build();

        when(repositoryRepository.findByIdAndWorkspaceId(repoId, personalWsId)).thenReturn(Optional.of(personalRepo));
        when(pullRequestRepository.findByRepositoryIdAndNumber(repoId, 7)).thenReturn(Optional.of(pr));
        when(repositoryRepository.findAllByWorkspaceId(personalWsId)).thenReturn(List.of(personalRepo));
        when(pullRequestRepository.findAllByRepositoryId(repoId)).thenReturn(List.of(pr));

        // 1. Get PR via personal workspace
        PullRequest fetchedPr = repositoryApplicationService.getPullRequest(null, personalWsId, repoId, 7);
        assertThat(fetchedPr).isNotNull();
        assertThat(fetchedPr.getNumber()).isEqualTo(7);
        assertThat(fetchedPr.getWorkspaceId()).isEqualTo(personalWsId);
        assertThat(fetchedPr.getOrganizationId()).isNull();

        // 2. List PRs via personal workspace (repo-specific and workspace-wide)
        List<PullRequest> prList = repositoryApplicationService.listPullRequests(null, personalWsId, repoId);
        assertThat(prList).hasSize(1);
        assertThat(prList.get(0).getNumber()).isEqualTo(7);

        List<PullRequest> allPrs = repositoryApplicationService.listPullRequests(null, personalWsId, null);
        assertThat(allPrs).hasSize(1);
        assertThat(allPrs.get(0).getNumber()).isEqualTo(7);

        // 3. Fallback PR Changeset retrieval via personal workspace
        var changeSet = repositoryApplicationService.getPullRequestChanges(null, personalWsId, repoId, 7);
        assertThat(changeSet).isNotNull();
        assertThat(changeSet.getNumber()).isEqualTo(7);
        assertThat(changeSet.getTitle()).isEqualTo("Add workspace support");
    }

    private static String generateRsaKey() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            String base64 = Base64.getEncoder().encodeToString(gen.generateKeyPair().getPrivate().getEncoded());
            return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
