package com.forgeai.sourcecontrol.application.service;

import com.forgeai.sourcecontrol.domain.event.RepositoryConnectedEvent;
import com.forgeai.sourcecontrol.domain.exception.DuplicateResourceException;
import com.forgeai.sourcecontrol.domain.exception.ResourceNotFoundException;
import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.model.InstallationStatus;
import com.forgeai.sourcecontrol.domain.model.PullRequest;
import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import com.forgeai.sourcecontrol.domain.repository.RepositoryRepository;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAccessDeniedException;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubRepositoryMetadata;
import com.forgeai.sourcecontrol.infrastructure.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for repository management.
 *
 * <p>All repository connection operations require a real GitHub App installation.
 * No fake installation IDs, no random repository IDs, and no caller-supplied
 * repository metadata are accepted. All authoritative data comes from GitHub.
 */
@Slf4j
@Service
public class RepositoryApplicationService {

    /** Quarantine sentinel — installations without a claimed organization are held here. */
    private static final UUID QUARANTINE_ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final RepositoryRepository repositoryRepository;
    private final GitHubInstallationRepository installationRepository;
    private final com.forgeai.sourcecontrol.domain.repository.PullRequestRepository pullRequestRepository;
    private final com.forgeai.sourcecontrol.domain.repository.CommitRepository commitRepository;
    private final OutboxService outboxService;
    private final GitHubAppClient gitHubAppClient;
    private final GitHubAppConfig gitHubAppConfig;
    private final com.forgeai.sourcecontrol.infrastructure.ai.AiServiceClient aiServiceClient;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.forgeai.sourcecontrol.infrastructure.metrics.InstallationTelemetryReconciler telemetryReconciler;

    public RepositoryApplicationService(
            RepositoryRepository repositoryRepository,
            GitHubInstallationRepository installationRepository,
            com.forgeai.sourcecontrol.domain.repository.PullRequestRepository pullRequestRepository,
            OutboxService outboxService,
            GitHubAppClient gitHubAppClient,
            GitHubAppConfig gitHubAppConfig) {
        this(repositoryRepository, installationRepository, pullRequestRepository, null, outboxService, gitHubAppClient, gitHubAppConfig, null);
    }

    public RepositoryApplicationService(
            RepositoryRepository repositoryRepository,
            GitHubInstallationRepository installationRepository,
            com.forgeai.sourcecontrol.domain.repository.PullRequestRepository pullRequestRepository,
            com.forgeai.sourcecontrol.domain.repository.CommitRepository commitRepository,
            OutboxService outboxService,
            GitHubAppClient gitHubAppClient,
            GitHubAppConfig gitHubAppConfig) {
        this(repositoryRepository, installationRepository, pullRequestRepository, commitRepository, outboxService, gitHubAppClient, gitHubAppConfig, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RepositoryApplicationService(
            RepositoryRepository repositoryRepository,
            GitHubInstallationRepository installationRepository,
            com.forgeai.sourcecontrol.domain.repository.PullRequestRepository pullRequestRepository,
            com.forgeai.sourcecontrol.domain.repository.CommitRepository commitRepository,
            OutboxService outboxService,
            GitHubAppClient gitHubAppClient,
            GitHubAppConfig gitHubAppConfig,
            com.forgeai.sourcecontrol.infrastructure.ai.AiServiceClient aiServiceClient) {
        this.repositoryRepository = repositoryRepository;
        this.installationRepository = installationRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.commitRepository = commitRepository;
        this.outboxService = outboxService;
        this.gitHubAppClient = gitHubAppClient;
        this.gitHubAppConfig = gitHubAppConfig;
        this.aiServiceClient = aiServiceClient;
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<com.forgeai.sourcecontrol.domain.model.PullRequest> listPullRequests(
            UUID organizationId, UUID repositoryId) {
        return listPullRequests(organizationId, null, repositoryId);
    }

    @Transactional(readOnly = true)
    public List<com.forgeai.sourcecontrol.domain.model.PullRequest> listPullRequests(
            UUID organizationId, UUID workspaceId, UUID repositoryId) {
        UUID effectiveWsId = resolveEffectiveWorkspaceId(organizationId, workspaceId);
        if (repositoryId != null) {
            getRepository(organizationId, effectiveWsId, repositoryId);
            return pullRequestRepository.findAllByRepositoryId(repositoryId);
        }
        if (organizationId != null) {
            return pullRequestRepository.findAllByOrganizationId(organizationId);
        }
        List<Repository> repos = repositoryRepository.findAllByWorkspaceId(effectiveWsId);
        return repos.stream()
                .filter(r -> r.getOrganizationId() == null && "PERSONAL".equalsIgnoreCase(r.getScope()))
                .flatMap(r -> pullRequestRepository.findAllByRepositoryId(r.getId()).stream())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Repository> listRepositories(UUID organizationId) {
        return listRepositories(organizationId, null);
    }

    @Transactional(readOnly = true)
    public List<Repository> listRepositories(UUID organizationId, UUID workspaceId) {
        UUID effectiveWsId = resolveEffectiveWorkspaceId(organizationId, workspaceId);
        if (organizationId != null) {
            return repositoryRepository.findAllByOrganizationId(organizationId);
        }
        return repositoryRepository.findAllByWorkspaceId(effectiveWsId).stream()
                .filter(r -> r.getOrganizationId() == null && "PERSONAL".equalsIgnoreCase(r.getScope()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Repository getRepository(UUID organizationId, UUID repositoryId) {
        return getRepository(organizationId, null, repositoryId);
    }

    @Transactional(readOnly = true)
    public Repository getRepository(UUID organizationId, UUID workspaceId, UUID repositoryId) {
        UUID effectiveWsId = resolveEffectiveWorkspaceId(organizationId, workspaceId);
        if (organizationId != null) {
            return repositoryRepository.findByIdAndOrganizationId(repositoryId, organizationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Repository not found: " + repositoryId));
        }
        Repository repo = repositoryRepository.findByIdAndWorkspaceId(repositoryId, effectiveWsId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found: " + repositoryId));
        if (repo.getOrganizationId() != null || !"PERSONAL".equalsIgnoreCase(repo.getScope())) {
            throw new ResourceNotFoundException("Repository not found: " + repositoryId);
        }
        return repo;
    }

    @Transactional(readOnly = true)
    public List<com.forgeai.sourcecontrol.domain.model.Commit> listCommits(UUID organizationId, UUID repositoryId) {
        return listCommits(organizationId, null, repositoryId);
    }

    @Transactional(readOnly = true)
    public List<com.forgeai.sourcecontrol.domain.model.Commit> listCommits(
            UUID organizationId, UUID workspaceId, UUID repositoryId) {
        requireTenantOrWorkspace(organizationId, workspaceId);
        getRepository(organizationId, workspaceId, repositoryId);
        return commitRepository.findAllByRepositoryId(repositoryId);
    }

    @Transactional(readOnly = true)
    public List<com.forgeai.sourcecontrol.infrastructure.github.GitHubBranchMetadata> listBranches(
            UUID organizationId, UUID repositoryId) {
        return listBranches(organizationId, null, repositoryId);
    }

    @Transactional(readOnly = true)
    public List<com.forgeai.sourcecontrol.infrastructure.github.GitHubBranchMetadata> listBranches(
            UUID organizationId, UUID workspaceId, UUID repositoryId) {
        requireTenantOrWorkspace(organizationId, workspaceId);
        Repository repo = getRepository(organizationId, workspaceId, repositoryId);
        return gitHubAppClient.listBranches(repo.getInstallationId(), repo.getFullName());
    }

    @Transactional
    public List<com.forgeai.sourcecontrol.domain.model.Commit> syncCommits(UUID organizationId, UUID repositoryId) {
        return syncCommits(organizationId, null, repositoryId);
    }

    @Transactional
    public List<com.forgeai.sourcecontrol.domain.model.Commit> syncCommits(
            UUID organizationId, UUID workspaceId, UUID repositoryId) {
        requireTenantOrWorkspace(organizationId, workspaceId);
        Repository repo = getRepository(organizationId, workspaceId, repositoryId);
        List<com.forgeai.sourcecontrol.infrastructure.github.GitHubCommitMetadata> ghCommits =
                gitHubAppClient.listCommits(repo.getInstallationId(), repo.getFullName(), 30);

        List<com.forgeai.sourcecontrol.domain.model.Commit> savedList = new java.util.ArrayList<>();
        for (var ghCommit : ghCommits) {
            Optional<com.forgeai.sourcecontrol.domain.model.Commit> existing =
                    commitRepository.findByRepositoryIdAndSha(repo.getId(), ghCommit.sha());
            if (existing.isPresent()) {
                com.forgeai.sourcecontrol.domain.model.Commit c = existing.get();
                c.setMessage(ghCommit.message());
                c.setAuthorName(ghCommit.authorName());
                c.setAuthorEmail(ghCommit.authorEmail());
                c.setCommittedAt(ghCommit.committedAt());
                savedList.add(commitRepository.save(c));
            } else {
                com.forgeai.sourcecontrol.domain.model.Commit c = com.forgeai.sourcecontrol.domain.model.Commit.builder()
                        .organizationId(repo.getOrganizationId())
                        .workspaceId(repo.getWorkspaceId())
                        .repositoryId(repo.getId())
                        .sha(ghCommit.sha())
                        .message(ghCommit.message())
                        .authorName(ghCommit.authorName())
                        .authorEmail(ghCommit.authorEmail())
                        .committedAt(ghCommit.committedAt())
                        .createdAt(Instant.now())
                        .build();
                savedList.add(commitRepository.save(c));
            }
        }
        log.info("[GitHub] Synchronized {} commits for repo: {}", savedList.size(), repo.getFullName());
        return savedList;
    }

    @Transactional
    public List<com.forgeai.sourcecontrol.domain.model.PullRequest> syncPullRequests(UUID organizationId, UUID repositoryId) {
        return syncPullRequests(organizationId, null, repositoryId);
    }

    @Transactional
    public List<com.forgeai.sourcecontrol.domain.model.PullRequest> syncPullRequests(
            UUID organizationId, UUID workspaceId, UUID repositoryId) {
        requireTenantOrWorkspace(organizationId, workspaceId);
        Repository repo = getRepository(organizationId, workspaceId, repositoryId);
        List<com.forgeai.sourcecontrol.infrastructure.github.GitHubPullRequestMetadata> ghPrs =
                gitHubAppClient.listPullRequests(repo.getInstallationId(), repo.getFullName());

        List<com.forgeai.sourcecontrol.domain.model.PullRequest> savedList = new java.util.ArrayList<>();
        for (var ghPr : ghPrs) {
            Optional<com.forgeai.sourcecontrol.domain.model.PullRequest> existing =
                    pullRequestRepository.findByRepositoryIdAndNumber(repo.getId(), ghPr.number());
            com.forgeai.sourcecontrol.domain.model.PullRequestStatus status =
                    "closed".equalsIgnoreCase(ghPr.state())
                            ? (ghPr.mergedAt() != null ? com.forgeai.sourcecontrol.domain.model.PullRequestStatus.MERGED : com.forgeai.sourcecontrol.domain.model.PullRequestStatus.CLOSED)
                            : com.forgeai.sourcecontrol.domain.model.PullRequestStatus.OPEN;

            if (existing.isPresent()) {
                var pr = existing.get();
                pr.setTitle(ghPr.title());
                pr.setBody(ghPr.body());
                pr.setSourceBranch(ghPr.sourceBranch());
                pr.setTargetBranch(ghPr.targetBranch());
                pr.setStatus(status);
                pr.setUpdatedAt(Instant.now());
                savedList.add(pullRequestRepository.save(pr));
            } else {
                var pr = com.forgeai.sourcecontrol.domain.model.PullRequest.builder()
                        .organizationId(repo.getOrganizationId())
                        .workspaceId(repo.getWorkspaceId())
                        .repositoryId(repo.getId())
                        .githubPrId(ghPr.githubPrId())
                        .number(ghPr.number())
                        .title(ghPr.title())
                        .body(ghPr.body())
                        .sourceBranch(ghPr.sourceBranch())
                        .targetBranch(ghPr.targetBranch())
                        .status(status)
                        .authorGithubId(ghPr.authorLogin())
                        .createdAt(ghPr.createdAt() != null ? ghPr.createdAt() : Instant.now())
                        .updatedAt(ghPr.updatedAt() != null ? ghPr.updatedAt() : Instant.now())
                        .build();
                savedList.add(pullRequestRepository.save(pr));
            }
        }
        log.info("[GitHub] Synchronized {} pull requests for repo: {}", savedList.size(), repo.getFullName());
        return savedList;
    }

    @Transactional
    public com.forgeai.sourcecontrol.domain.model.PullRequestChangeSet getPullRequestChanges(
            UUID organizationId, UUID repositoryId, int prNumber) {
        return getPullRequestChanges(organizationId, null, repositoryId, prNumber);
    }

    @Transactional
    public com.forgeai.sourcecontrol.domain.model.PullRequestChangeSet getPullRequestChanges(
            UUID organizationId, UUID workspaceId, UUID repositoryId, int prNumber) {
        requireTenantOrWorkspace(organizationId, workspaceId);
        Repository repo = getRepository(organizationId, workspaceId, repositoryId);

        if (gitHubAppConfig.isConfigured()) {
            try {
                var detail = gitHubAppClient.getPullRequestDetail(repo.getInstallationId(), repo.getFullName(), prNumber);
                List<com.forgeai.sourcecontrol.infrastructure.github.GitHubPullRequestFile> enrichedFiles = new java.util.ArrayList<>();
                if (detail.files() != null) {
                    for (var f : detail.files()) {
                        String content = null;
                        if (!"removed".equalsIgnoreCase(f.status())) {
                            content = gitHubAppClient.getFileContent(repo.getInstallationId(), repo.getFullName(), f.filename(), detail.headSha())
                                    .orElse(null);
                        }
                        enrichedFiles.add(new com.forgeai.sourcecontrol.infrastructure.github.GitHubPullRequestFile(
                                f.filename(), f.status(), f.additions(), f.deletions(), f.changes(),
                                f.patch(), f.rawUrl(), f.contentsUrl(), content
                        ));
                    }
                }

                // Update or save local pull request record
                Optional<com.forgeai.sourcecontrol.domain.model.PullRequest> existing =
                        pullRequestRepository.findByRepositoryIdAndNumber(repo.getId(), prNumber);
                com.forgeai.sourcecontrol.domain.model.PullRequestStatus status =
                        "closed".equalsIgnoreCase(detail.state())
                                ? com.forgeai.sourcecontrol.domain.model.PullRequestStatus.CLOSED
                                : com.forgeai.sourcecontrol.domain.model.PullRequestStatus.OPEN;

                if (existing.isPresent()) {
                    var pr = existing.get();
                    pr.setTitle(detail.title());
                    pr.setBody(detail.body());
                    pr.setSourceBranch(detail.sourceBranch());
                    pr.setTargetBranch(detail.targetBranch());
                    pr.setStatus(status);
                    pr.setUpdatedAt(Instant.now());
                    pullRequestRepository.save(pr);
                } else {
                    var pr = com.forgeai.sourcecontrol.domain.model.PullRequest.builder()
                            .organizationId(repo.getOrganizationId())
                            .workspaceId(repo.getWorkspaceId())
                            .repositoryId(repo.getId())
                            .githubPrId(detail.githubPrId())
                            .number(detail.number())
                            .title(detail.title())
                            .body(detail.body())
                            .sourceBranch(detail.sourceBranch())
                            .targetBranch(detail.targetBranch())
                            .status(status)
                            .authorGithubId(detail.authorLogin())
                            .createdAt(detail.createdAt() != null ? detail.createdAt() : Instant.now())
                            .updatedAt(detail.updatedAt() != null ? detail.updatedAt() : Instant.now())
                            .build();
                    pullRequestRepository.save(pr);
                }

                return com.forgeai.sourcecontrol.domain.model.PullRequestChangeSet.builder()
                        .repositoryId(repo.getId())
                        .repositoryFullName(repo.getFullName())
                        .number(detail.number())
                        .title(detail.title())
                        .body(detail.body())
                        .state(detail.state())
                        .sourceBranch(detail.sourceBranch())
                        .targetBranch(detail.targetBranch())
                        .baseSha(detail.baseSha())
                        .headSha(detail.headSha())
                        .author(detail.authorLogin())
                        .additions(detail.additions())
                        .deletions(detail.deletions())
                        .changedFilesCount(detail.changedFilesCount())
                        .files(enrichedFiles)
                        .createdAt(detail.createdAt())
                        .updatedAt(detail.updatedAt())
                        .build();

            } catch (Exception e) {
                log.warn("[GitHub] Could not fetch real-time PR changeset from GitHub API ({}); falling back to persisted PR record", e.getMessage());
            }
        }

        // Fallback to persisted pull request record
        var pr = pullRequestRepository.findByRepositoryIdAndNumber(repo.getId(), prNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Pull request #" + prNumber + " not found for repository " + repositoryId));

        return com.forgeai.sourcecontrol.domain.model.PullRequestChangeSet.builder()
                .repositoryId(repo.getId())
                .repositoryFullName(repo.getFullName())
                .number(pr.getNumber())
                .title(pr.getTitle())
                .body(pr.getBody())
                .state(pr.getStatus() != null ? pr.getStatus().name() : "OPEN")
                .sourceBranch(pr.getSourceBranch())
                .targetBranch(pr.getTargetBranch())
                .baseSha("")
                .headSha("")
                .author(pr.getAuthorGithubId())
                .additions(0)
                .deletions(0)
                .changedFilesCount(0)
                .files(List.of())
                .createdAt(pr.getCreatedAt())
                .updatedAt(pr.getUpdatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public PullRequest getPullRequest(UUID organizationId, UUID repositoryId, int prNumber) {
        return getPullRequest(organizationId, null, repositoryId, prNumber);
    }

    @Transactional(readOnly = true)
    public PullRequest getPullRequest(UUID organizationId, UUID workspaceId, UUID repositoryId, int prNumber) {
        requireTenantOrWorkspace(organizationId, workspaceId);
        Repository repo = getRepository(organizationId, workspaceId, repositoryId);
        return pullRequestRepository.findByRepositoryIdAndNumber(repo.getId(), prNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Pull request #" + prNumber + " not found for repository " + repositoryId));
    }


    // -------------------------------------------------------------------------
    // Installation claim
    // -------------------------------------------------------------------------

    /**
     * Claims a quarantined GitHub installation for an authenticated ForgeAI organization.
     *
     * <p>Security contract:
     * <ul>
     *   <li>Installation must exist in the local DB with status {@link InstallationStatus#SUSPENDED}
     *       and organization ID {@link #QUARANTINE_ORG_ID}.</li>
     *   <li>The GitHub App must be configured — we call GitHub to confirm the installation exists.</li>
     *   <li>Only the authenticated caller's organization is assigned — never guessed.</li>
     *   <li>An organization may claim at most one installation (one-to-one mapping).</li>
     * </ul>
     *
     * @param organizationId  authenticated organization (from JWT/gateway)
     * @param installationId  GitHub installation_id to claim
     * @return the updated {@link GitHubInstallation}
     */
    @Transactional
    public GitHubInstallation claimInstallation(UUID organizationId, Long installationId) {
        return claimInstallation(organizationId, null, installationId);
    }

    @Transactional
    public GitHubInstallation claimInstallation(UUID organizationId, UUID workspaceId, Long installationId) {
        requireTenantOrWorkspace(organizationId, workspaceId);
        gitHubAppConfig.requireConfigured();

        UUID targetWorkspaceId = workspaceId != null ? workspaceId : organizationId;
        String scope = organizationId != null ? "ORGANIZATION" : "PERSONAL";

        // Must not be already claimed by this or another real org / workspace
        Optional<GitHubInstallation> alreadyClaimed = installationRepository.findByInstallationId(installationId);
        if (alreadyClaimed.isPresent()) {
            GitHubInstallation existing = alreadyClaimed.get();
            if (!QUARANTINE_ORG_ID.equals(existing.getOrganizationId())) {
                if (organizationId != null && organizationId.equals(existing.getOrganizationId())) {
                    return existing;
                }
                if (targetWorkspaceId != null && targetWorkspaceId.equals(existing.getWorkspaceId())) {
                    return existing;
                }
                throw new GitHubAccessDeniedException(
                        "Installation " + installationId + " is already claimed by another organization or workspace.");
            }
        }

        // Verify the installation actually exists on GitHub
        String accountLogin = gitHubAppClient.getInstallationAccountLogin(installationId);
        log.info("[GitHub] Verified installation {} belongs to GitHub account '{}'", installationId, accountLogin);

        // Check if tenant already owns a different installation
        Optional<GitHubInstallation> existingInstall = organizationId != null
                ? installationRepository.findByOrganizationId(organizationId)
                : installationRepository.findByWorkspaceId(targetWorkspaceId);

        if (existingInstall.isPresent() && !QUARANTINE_ORG_ID.equals(existingInstall.get().getOrganizationId())) {
            if (!installationId.equals(existingInstall.get().getInstallationId())) {
                throw new GitHubAccessDeniedException(
                        "Tenant already has a claimed GitHub installation (" + existingInstall.get().getInstallationId() + "). " +
                        "Remove it before claiming a different installation.");
            }
        }

        if (alreadyClaimed.isPresent()) {
            // Promote quarantined record
            GitHubInstallation inst = alreadyClaimed.get();
            inst.setOrganizationId(organizationId);
            inst.setWorkspaceId(targetWorkspaceId);
            inst.setScope(scope);
            inst.setStatus(InstallationStatus.ACTIVE);
            inst.setUpdatedAt(Instant.now());
            GitHubInstallation saved = installationRepository.save(inst);
            if (telemetryReconciler != null) telemetryReconciler.reconcile();
            log.info("[GitHub] Installation {} claimed by orgId={}, wsId={}", installationId, organizationId, targetWorkspaceId);
            return saved;
        } else {
            // Installation exists on GitHub but not yet in our DB (e.g. webhook not yet received)
            GitHubInstallation inst = GitHubInstallation.builder()
                    .organizationId(organizationId)
                    .workspaceId(targetWorkspaceId)
                    .scope(scope)
                    .installationId(installationId)
                    .accountLogin(accountLogin)
                    .accountType("PERSONAL".equals(scope) ? "User" : "Organization")
                    .status(InstallationStatus.ACTIVE)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            GitHubInstallation saved = installationRepository.save(inst);
            if (telemetryReconciler != null) telemetryReconciler.reconcile();
            log.info("[GitHub] New installation {} registered and claimed by orgId={}, wsId={}", installationId, organizationId, targetWorkspaceId);
            return saved;
        }
    }

    /**
     * Lists all GitHub repositories accessible to the installation claimed by this organization or workspace.
     * All data comes from GitHub — no local cache.
     */
    @Transactional(readOnly = true)
    public List<GitHubRepositoryMetadata> listAvailableRepositories(UUID organizationId) {
        return listAvailableRepositories(organizationId, null);
    }

    @Transactional(readOnly = true)
    public List<GitHubRepositoryMetadata> listAvailableRepositories(UUID organizationId, UUID workspaceId) {
        requireTenantOrWorkspace(organizationId, workspaceId);
        gitHubAppConfig.requireConfigured();

        GitHubInstallation installation = getClaimedInstallation(organizationId, workspaceId);
        return gitHubAppClient.listInstallationRepositories(installation.getInstallationId());
    }

    // -------------------------------------------------------------------------
    // Repository connection (production-only, no fake data)
    // -------------------------------------------------------------------------

    /**
     * Connects a repository to this ForgeAI organization or workspace.
     */
    @Transactional
    public Repository connectRepository(UUID organizationId, String fullName) {
        return connectRepository(organizationId, null, fullName);
    }

    @Transactional
    public Repository connectRepository(UUID organizationId, UUID workspaceId, String fullName) {
        requireTenantOrWorkspace(organizationId, workspaceId);
        gitHubAppConfig.requireConfigured();

        if (fullName == null || fullName.isBlank() || !fullName.contains("/")) {
            throw new IllegalArgumentException(
                    "fullName must be in 'owner/repo' format, e.g. 'acme/my-service'");
        }

        UUID targetWorkspaceId = workspaceId != null ? workspaceId : organizationId;
        String scope = organizationId != null ? "ORGANIZATION" : "PERSONAL";

        GitHubInstallation installation = getClaimedInstallation(organizationId, workspaceId);

        // Fetch authoritative metadata from GitHub and verify access
        GitHubRepositoryMetadata ghRepo = gitHubAppClient.getVerifiedRepository(
                installation.getInstallationId(), fullName);

        // Duplicate check and idempotent reconciliation
        Optional<Repository> existing = repositoryRepository.findByGithubRepoId(ghRepo.id());
        if (existing.isPresent()) {
            Repository existingRepo = existing.get();
            boolean isOwner = false;
            if ("ORGANIZATION".equalsIgnoreCase(existingRepo.getScope())) {
                isOwner = organizationId != null && organizationId.equals(existingRepo.getOrganizationId());
            } else if ("PERSONAL".equalsIgnoreCase(existingRepo.getScope())) {
                isOwner = organizationId == null && targetWorkspaceId != null && targetWorkspaceId.equals(existingRepo.getWorkspaceId());
            }
            if (isOwner) {
                existingRepo.setName(ghRepo.name());
                existingRepo.setFullName(ghRepo.fullName());
                existingRepo.setDefaultBranch(ghRepo.defaultBranch());
                existingRepo.setIsPrivate(ghRepo.isPrivate());
                existingRepo.setInstallationId(installation.getInstallationId());
                existingRepo.setUpdatedAt(Instant.now());
                Repository updated = repositoryRepository.save(existingRepo);
                log.info("[GitHub] Idempotently reconciled repository: id={}, fullName={}, githubRepoId={}, orgId={}, wsId={}",
                        updated.getId(), updated.getFullName(), updated.getGithubRepoId(), organizationId, targetWorkspaceId);
                return updated;
            }
            throw new DuplicateResourceException(
                    "Repository '" + fullName + "' (GitHub ID " + ghRepo.id() + ") is already connected to another organization or workspace.");
        }

        Repository repo = Repository.builder()
                .organizationId(organizationId)
                .workspaceId(targetWorkspaceId)
                .scope(scope)
                .installationId(installation.getInstallationId())
                .githubRepoId(ghRepo.id())
                .name(ghRepo.name())
                .fullName(ghRepo.fullName())
                .defaultBranch(ghRepo.defaultBranch())
                .isPrivate(ghRepo.isPrivate())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        Repository saved = repositoryRepository.save(repo);

        RepositoryConnectedEvent event = RepositoryConnectedEvent.builder()
                .repositoryId(saved.getId())
                .organizationId(organizationId)
                .workspaceId(targetWorkspaceId)
                .githubRepoId(saved.getGithubRepoId())
                .fullName(saved.getFullName())
                .defaultBranch(saved.getDefaultBranch())
                .timestamp(Instant.now())
                .build();

        outboxService.publish(event, "connect-" + saved.getId(), "Repository", saved.getId().toString());
        log.info("[GitHub] Connected repository: id={}, fullName={}, githubRepoId={}, orgId={}, wsId={}",
                saved.getId(), saved.getFullName(), saved.getGithubRepoId(), organizationId, targetWorkspaceId);
        return saved;
    }

    /**
     * Returns the active claimed installation for this organization or workspace, or throws
     * {@link ResourceNotFoundException} if none exists.
     */
    @Transactional(readOnly = true)
    public GitHubInstallation getClaimedInstallation(UUID organizationId) {
        return getClaimedInstallation(organizationId, null);
    }

    @Transactional(readOnly = true)
    public GitHubInstallation getClaimedInstallation(UUID organizationId, UUID workspaceId) {
        UUID effectiveWsId = resolveEffectiveWorkspaceId(organizationId, workspaceId);
        Optional<GitHubInstallation> opt;
        if (organizationId != null) {
            opt = installationRepository.findByOrganizationId(organizationId);
        } else {
            opt = installationRepository.findByWorkspaceId(effectiveWsId)
                    .filter(i -> i.getOrganizationId() == null && "PERSONAL".equalsIgnoreCase(i.getScope()));
        }

        return opt.filter(i -> InstallationStatus.ACTIVE.equals(i.getStatus()))
                .filter(i -> !QUARANTINE_ORG_ID.equals(i.getOrganizationId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active GitHub App installation found for this tenant. " +
                        "Install the ForgeAI GitHub App and claim the installation first."));
    }


    // -------------------------------------------------------------------------
    // Synchronization & Reconciliation
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public com.forgeai.sourcecontrol.domain.model.RepositorySyncStatusDTO getSyncStatus(UUID organizationId, UUID repositoryId) {
        return getSyncStatus(organizationId, null, repositoryId);
    }

    @Transactional(readOnly = true)
    public com.forgeai.sourcecontrol.domain.model.RepositorySyncStatusDTO getSyncStatus(
            UUID organizationId, UUID workspaceId, UUID repositoryId) {
        requireTenantOrWorkspace(organizationId, workspaceId);
        Repository repo = getRepository(organizationId, workspaceId, repositoryId);

        com.forgeai.sourcecontrol.domain.model.RepositorySyncStatus status = repo.getSyncStatus();
        if (status == null) {
            status = com.forgeai.sourcecontrol.domain.model.RepositorySyncStatus.SYNCED;
        }

        if (status != com.forgeai.sourcecontrol.domain.model.RepositorySyncStatus.SYNCING &&
            status != com.forgeai.sourcecontrol.domain.model.RepositorySyncStatus.FAILED) {
            if (repo.getLatestObservedSha() != null &&
                !repo.getLatestObservedSha().isBlank() &&
                !repo.getLatestObservedSha().equals(repo.getLatestIndexedSha())) {
                status = com.forgeai.sourcecontrol.domain.model.RepositorySyncStatus.STALE;
            }
        }

        return com.forgeai.sourcecontrol.domain.model.RepositorySyncStatusDTO.builder()
                .repositoryId(repo.getId())
                .repositoryFullName(repo.getFullName())
                .defaultBranch(repo.getDefaultBranch())
                .currentGithubHead(repo.getLatestObservedSha())
                .indexedHead(repo.getLatestIndexedSha())
                .syncStatus(status)
                .lastSyncedAt(repo.getLastSyncedAt())
                .lastError(repo.getLastError())
                .build();
    }

    @Transactional
    public com.forgeai.sourcecontrol.domain.model.RepositorySyncStatusDTO reconcileRepository(UUID organizationId, UUID repositoryId) {
        return reconcileRepository(organizationId, null, repositoryId);
    }

    @Transactional
    public com.forgeai.sourcecontrol.domain.model.RepositorySyncStatusDTO reconcileRepository(
            UUID organizationId, UUID workspaceId, UUID repositoryId) {
        requireTenantOrWorkspace(organizationId, workspaceId);
        Repository repo = getRepository(organizationId, workspaceId, repositoryId);

        String currentHead = null;
        if (gitHubAppConfig.isConfigured() && repo.getInstallationId() != null && repo.getInstallationId() > 0) {
            try {
                // Find default branch HEAD
                var branches = gitHubAppClient.listBranches(repo.getInstallationId(), repo.getFullName());
                for (var b : branches) {
                    if (repo.getDefaultBranch().equalsIgnoreCase(b.name())) {
                        currentHead = b.commitSha();
                        break;
                    }
                }
                if (currentHead == null) {
                    var commits = gitHubAppClient.listCommits(repo.getInstallationId(), repo.getFullName(), 1);
                    if (!commits.isEmpty()) {
                        currentHead = commits.get(0).sha();
                    }
                }
            } catch (Exception e) {
                log.warn("[GitHub] Could not query remote HEAD during reconciliation for repo {}: {}", repo.getFullName(), e.getMessage());
            }
        }

        if (currentHead != null && !currentHead.isBlank()) {
            repo.setLatestObservedSha(currentHead);
        } else if (repo.getLatestObservedSha() != null) {
            currentHead = repo.getLatestObservedSha();
        }

        if (currentHead != null && !currentHead.isBlank()) {
            if (!currentHead.equals(repo.getLatestIndexedSha())) {
                repo.setSyncStatus(com.forgeai.sourcecontrol.domain.model.RepositorySyncStatus.SYNCING);
                repositoryRepository.save(repo);

                boolean syncSuccess = true;
                if (aiServiceClient != null) {
                    syncSuccess = aiServiceClient.syncPush(
                            repo.getOrganizationId() != null ? repo.getOrganizationId() : repo.getWorkspaceId(),
                            repo.getId(),
                            currentHead,
                            "refs/heads/" + repo.getDefaultBranch(),
                            java.util.List.of()
                    );
                }

                if (syncSuccess) {
                    repo.setLatestIndexedSha(currentHead);
                    repo.setSyncStatus(com.forgeai.sourcecontrol.domain.model.RepositorySyncStatus.SYNCED);
                    repo.setLastSyncedAt(Instant.now());
                    repo.setLastError(null);
                } else {
                    repo.setSyncStatus(com.forgeai.sourcecontrol.domain.model.RepositorySyncStatus.FAILED);
                    repo.setLastError("Reconciliation failed for commit " + currentHead);
                }
            } else {
                repo.setSyncStatus(com.forgeai.sourcecontrol.domain.model.RepositorySyncStatus.SYNCED);
                repo.setLastSyncedAt(Instant.now());
                repo.setLastError(null);
            }
        } else {
            repo.setSyncStatus(com.forgeai.sourcecontrol.domain.model.RepositorySyncStatus.SYNCED);
            repo.setLastSyncedAt(Instant.now());
        }

        repositoryRepository.save(repo);
        return getSyncStatus(organizationId, workspaceId, repositoryId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void requireTenant(UUID organizationId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must be provided via tenant context");
        }
    }

    private UUID resolveEffectiveWorkspaceId(UUID organizationId, UUID workspaceId) {
        if (organizationId == null && workspaceId == null) {
            throw new IllegalArgumentException("Either organization ID or workspace ID must be provided");
        }
        if (organizationId != null && workspaceId != null && !organizationId.equals(workspaceId)) {
            throw new IllegalArgumentException(
                    "Organization context " + organizationId + " does not match workspace context " + workspaceId);
        }
        return workspaceId != null ? workspaceId : organizationId;
    }

    private void requireTenantOrWorkspace(UUID organizationId, UUID workspaceId) {
        resolveEffectiveWorkspaceId(organizationId, workspaceId);
    }
}

