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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.forgeai.sourcecontrol.domain.model.Commit;
import com.forgeai.sourcecontrol.infrastructure.ai.AiServiceClient;

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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataWorkspaceRepository workspaceRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.forgeai.sourcecontrol.infrastructure.identity.IdentityServiceClient identityServiceClient;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.forgeai.sourcecontrol.infrastructure.messaging.WorkflowRunTestingServiceForwarder workflowRunTestingServiceForwarder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private GitHubInstallationDiscoveryService discoveryService;

    public void setDiscoveryService(GitHubInstallationDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    public void setWorkspaceRepository(com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataWorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    public void setIdentityServiceClient(com.forgeai.sourcecontrol.infrastructure.identity.IdentityServiceClient identityServiceClient) {
        this.identityServiceClient = identityServiceClient;
    }

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
        return listPullRequests(organizationId, workspaceId, repositoryId, com.forgeai.sourcecontrol.infrastructure.security.TenantContext.getUserId());
    }

    @Transactional
    public List<com.forgeai.sourcecontrol.domain.model.PullRequest> listPullRequests(
            UUID organizationId, UUID workspaceId, UUID repositoryId, UUID callerUserId) {
        UUID effectiveWsId = resolveEffectiveWorkspaceId(organizationId, workspaceId);
        validateWorkspaceAccess(effectiveWsId, organizationId, callerUserId);
        if (repositoryId != null) {
            getRepository(organizationId, effectiveWsId, repositoryId, callerUserId);
            List<com.forgeai.sourcecontrol.domain.model.PullRequest> prs = pullRequestRepository.findAllByRepositoryId(repositoryId);
            if (prs.isEmpty() && gitHubAppConfig != null && gitHubAppConfig.isConfigured()) {
                try {
                    prs = syncPullRequests(organizationId, effectiveWsId, repositoryId);
                } catch (Exception e) {
                    log.warn("Automatic PR sync failed for repository {}: {}", repositoryId, e.getMessage());
                }
            }
            return prs != null ? prs : java.util.Collections.emptyList();
        }
        if (organizationId != null && !isPersonalWorkspace(effectiveWsId)) {
            List<com.forgeai.sourcecontrol.domain.model.PullRequest> orgPrs = pullRequestRepository.findAllByOrganizationId(organizationId);
            if (!orgPrs.isEmpty()) {
                return orgPrs;
            }
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
        return listRepositories(organizationId, workspaceId, com.forgeai.sourcecontrol.infrastructure.security.TenantContext.getUserId());
    }

    @Transactional(readOnly = true)
    public List<Repository> listRepositories(UUID organizationId, UUID workspaceId, UUID callerUserId) {
        UUID effectiveWsId = resolveEffectiveWorkspaceId(organizationId, workspaceId);
        validateWorkspaceAccess(effectiveWsId, organizationId, callerUserId);
        List<Repository> result;
        if (organizationId != null && !isPersonalWorkspace(effectiveWsId)) {
            List<Repository> orgRepos = repositoryRepository.findAllByOrganizationId(organizationId);
            if (!orgRepos.isEmpty()) {
                result = orgRepos;
            } else {
                result = java.util.Collections.emptyList();
            }
        } else {
            List<Repository> wsRepos = repositoryRepository.findAllByWorkspaceId(effectiveWsId);
            if (!wsRepos.isEmpty()) {
                result = wsRepos.stream()
                        .filter(r -> r.getOrganizationId() == null && "PERSONAL".equalsIgnoreCase(r.getScope()))
                        .toList();
            } else if (organizationId != null) {
                result = repositoryRepository.findAllByOrganizationId(organizationId);
            } else {
                result = java.util.Collections.emptyList();
            }
        }
        return result.stream()
                .sorted(java.util.Comparator.comparing((Repository r) -> r.getFullName() != null ? r.getFullName().toLowerCase() : "")
                        .thenComparing(r -> r.getId() != null ? r.getId() : UUID.randomUUID()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Repository getRepository(UUID organizationId, UUID repositoryId) {
        return getRepository(organizationId, null, repositoryId);
    }

    @Transactional(readOnly = true)
    public Repository getRepository(UUID organizationId, UUID workspaceId, UUID repositoryId) {
        return getRepository(organizationId, workspaceId, repositoryId, com.forgeai.sourcecontrol.infrastructure.security.TenantContext.getUserId());
    }

    @Transactional(readOnly = true)
    public Repository getRepository(UUID organizationId, UUID workspaceId, UUID repositoryId, UUID callerUserId) {
        UUID effectiveWsId = resolveEffectiveWorkspaceId(organizationId, workspaceId);
        validateWorkspaceAccess(effectiveWsId, organizationId, callerUserId);
        if (organizationId != null && !isPersonalWorkspace(effectiveWsId)) {
            var repoOpt = repositoryRepository.findByIdAndOrganizationId(repositoryId, organizationId);
            if (repoOpt.isPresent()) {
                return repoOpt.get();
            }
        }
        Repository repo = repositoryRepository.findByIdAndWorkspaceId(repositoryId, effectiveWsId)
                .or(() -> organizationId != null ? repositoryRepository.findByIdAndOrganizationId(repositoryId, organizationId) : java.util.Optional.empty())
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found: " + repositoryId));
        if (repo.getOrganizationId() == null && !"PERSONAL".equalsIgnoreCase(repo.getScope())) {
            throw new ResourceNotFoundException("Repository not found: " + repositoryId);
        }
        validateWorkspaceAccess(repo.getWorkspaceId(), organizationId, callerUserId);
        return repo;
    }

    @Transactional(readOnly = true)
    public List<com.forgeai.sourcecontrol.domain.model.Commit> listCommits(UUID organizationId, UUID repositoryId) {
        return listCommits(organizationId, null, repositoryId);
    }

    @Transactional(readOnly = true)
    public List<com.forgeai.sourcecontrol.domain.model.Commit> listCommits(
            UUID organizationId, UUID workspaceId, UUID repositoryId) {
        return listCommits(organizationId, workspaceId, repositoryId, null);
    }

    @Transactional(readOnly = true)
    public List<com.forgeai.sourcecontrol.domain.model.Commit> listCommits(
            UUID organizationId, UUID workspaceId, UUID repositoryId, String branch) {
        requireTenantOrWorkspace(organizationId, workspaceId);
        getRepository(organizationId, workspaceId, repositoryId);
        if (branch != null && !branch.isBlank()) {
            return syncCommits(organizationId, workspaceId, repositoryId, branch);
        }
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
        if (gitHubAppConfig.isConfigured() && repo.getInstallationId() != null && repo.getInstallationId() > 0) {
            try {
                var branches = gitHubAppClient.listBranches(repo.getInstallationId(), repo.getFullName());
                if (branches != null && !branches.isEmpty()) {
                    return branches;
                }
            } catch (Exception e) {
                log.warn("[GitHub] Could not fetch branches for {}: {}", repo.getFullName(), e.getMessage());
            }
        }
        String defaultBranch = repo.getDefaultBranch() != null ? repo.getDefaultBranch() : "main";
        String sha = repo.getLatestObservedSha() != null ? repo.getLatestObservedSha() : "HEAD";
        return List.of(new com.forgeai.sourcecontrol.infrastructure.github.GitHubBranchMetadata(defaultBranch, sha, true));
    }

    @Transactional
    public List<com.forgeai.sourcecontrol.domain.model.Commit> syncCommits(UUID organizationId, UUID repositoryId) {
        return syncCommits(organizationId, null, repositoryId, null);
    }

    @Transactional
    public List<com.forgeai.sourcecontrol.domain.model.Commit> syncCommits(
            UUID organizationId, UUID workspaceId, UUID repositoryId) {
        return syncCommits(organizationId, workspaceId, repositoryId, null);
    }

    @Transactional
    public List<com.forgeai.sourcecontrol.domain.model.Commit> syncCommits(
            UUID organizationId, UUID workspaceId, UUID repositoryId, String branch) {
        requireTenantOrWorkspace(organizationId, workspaceId);
        Repository repo = getRepository(organizationId, workspaceId, repositoryId);
        List<com.forgeai.sourcecontrol.infrastructure.github.GitHubCommitMetadata> ghCommits =
                gitHubAppClient.listCommits(repo.getInstallationId(), repo.getFullName(), branch, 30);

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
        log.info("[GitHub] Synchronized {} commits for repo: {} (branch: {})", savedList.size(), repo.getFullName(), branch);
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

        if (ghPrs == null || ghPrs.isEmpty()) {
            return java.util.Collections.emptyList();
        }

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
        return claimInstallation(organizationId, workspaceId, installationId, com.forgeai.sourcecontrol.infrastructure.security.TenantContext.getUserId());
    }

    @Transactional
    public GitHubInstallation claimInstallation(UUID organizationId, UUID workspaceId, Long installationId, UUID callerUserId) {
        requireTenantOrWorkspace(organizationId, workspaceId);
        UUID targetWorkspaceId = workspaceId != null ? workspaceId : organizationId;
        validateWorkspaceAccess(targetWorkspaceId, organizationId, callerUserId);
        gitHubAppConfig.requireConfigured();

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

        // Strict Ownership Validation (Anti-Spoofing / Anti-Hijacking)
        if ("PERSONAL".equals(scope) && callerUserId != null && identityServiceClient != null) {
            var profileOpt = identityServiceClient.getUserGitHubProfile(callerUserId);
            if (profileOpt.isEmpty() || !profileOpt.get().connected()) {
                throw new GitHubAccessDeniedException("User must connect GitHub account before claiming a GitHub App installation.");
            }
            var profile = profileOpt.get();
            boolean loginMatches = profile.githubLogin() != null && profile.githubLogin().equalsIgnoreCase(accountLogin);
            if (!loginMatches) {
                log.warn("[Security] Installation claim ownership mismatch! Caller login '{}' != Installation account '{}'",
                        profile.githubLogin(), accountLogin);
                throw new GitHubAccessDeniedException(
                        "Installation " + installationId + " belongs to GitHub account '" + accountLogin +
                                "', which does not match your authenticated GitHub account.");
            }
        }

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
            inst.setClaimedByUserId(callerUserId);
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
                    .claimedByUserId(callerUserId)
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
        return listAvailableRepositories(organizationId, workspaceId, com.forgeai.sourcecontrol.infrastructure.security.TenantContext.getUserId());
    }

    @Transactional(readOnly = true)
    public List<GitHubRepositoryMetadata> listAvailableRepositories(UUID organizationId, UUID workspaceId, UUID callerUserId) {
        requireTenantOrWorkspace(organizationId, workspaceId);
        UUID effectiveWsId = resolveEffectiveWorkspaceId(organizationId, workspaceId);
        validateWorkspaceAccess(effectiveWsId, organizationId, callerUserId);
        gitHubAppConfig.requireConfigured();

        GitHubInstallation installation = getClaimedInstallation(organizationId, workspaceId, callerUserId);
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
        return connectRepository(organizationId, workspaceId, fullName, com.forgeai.sourcecontrol.infrastructure.security.TenantContext.getUserId());
    }

    @Transactional
    public Repository connectRepository(UUID organizationId, UUID workspaceId, String fullName, UUID callerUserId) {
        requireTenantOrWorkspace(organizationId, workspaceId);
        UUID targetWorkspaceId = workspaceId != null ? workspaceId : organizationId;
        validateWorkspaceAccess(targetWorkspaceId, organizationId, callerUserId);
        gitHubAppConfig.requireConfigured();

        if (fullName == null || fullName.isBlank() || !fullName.contains("/")) {
            throw new IllegalArgumentException(
                    "fullName must be in 'owner/repo' format, e.g. 'acme/my-service'");
        }

        String scope = organizationId != null ? "ORGANIZATION" : "PERSONAL";

        GitHubInstallation installation = getClaimedInstallation(organizationId, workspaceId, callerUserId);

        // Fetch authoritative metadata from GitHub and verify access
        GitHubRepositoryMetadata ghRepo = gitHubAppClient.getVerifiedRepository(
                installation.getInstallationId(), fullName);

        // Duplicate check and idempotent reconciliation
        Optional<Repository> existing = repositoryRepository.findByGithubRepoId(ghRepo.id());
        if (existing.isPresent()) {
            Repository existingRepo = existing.get();
            boolean isOwner = false;
            if (existingRepo.getInstallationId() != null && existingRepo.getInstallationId().equals(installation.getInstallationId())) {
                isOwner = true;
            } else if ("ORGANIZATION".equalsIgnoreCase(existingRepo.getScope())) {
                isOwner = organizationId != null && organizationId.equals(existingRepo.getOrganizationId());
            } else if ("PERSONAL".equalsIgnoreCase(existingRepo.getScope())) {
                isOwner = organizationId == null && (existingRepo.getWorkspaceId() == null || (targetWorkspaceId != null && targetWorkspaceId.equals(existingRepo.getWorkspaceId())));
            }
            if (isOwner) {
                existingRepo.setOrganizationId(organizationId);
                existingRepo.setWorkspaceId(targetWorkspaceId);
                existingRepo.setScope(scope);
                existingRepo.setName(ghRepo.name());
                existingRepo.setFullName(ghRepo.fullName());
                existingRepo.setDefaultBranch(ghRepo.defaultBranch());
                existingRepo.setIsPrivate(ghRepo.isPrivate());
                existingRepo.setInstallationId(installation.getInstallationId());
                existingRepo.setUpdatedAt(Instant.now());
                Repository updated = repositoryRepository.save(existingRepo);
                if (updated == null) {
                    updated = existingRepo;
                }
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
        return getClaimedInstallation(organizationId, workspaceId, com.forgeai.sourcecontrol.infrastructure.security.TenantContext.getUserId());
    }

    @Transactional
    public GitHubInstallation getClaimedInstallation(UUID organizationId, UUID workspaceId, UUID callerUserId) {
        UUID effectiveWsId = resolveEffectiveWorkspaceId(organizationId, workspaceId);
        validateWorkspaceAccess(effectiveWsId, organizationId, callerUserId);
        Optional<GitHubInstallation> opt;
        if (organizationId != null) {
            opt = installationRepository.findByOrganizationId(organizationId);
        } else {
            opt = installationRepository.findByWorkspaceId(effectiveWsId)
                    .filter(i -> i.getOrganizationId() == null && "PERSONAL".equalsIgnoreCase(i.getScope()));
            if (opt.isEmpty() && callerUserId != null) {
                opt = findAndBindPersonalInstallation(callerUserId, effectiveWsId);
            }
        }

        if (opt.isEmpty()) {
            opt = installationRepository.findAll().stream()
                    .filter(i -> InstallationStatus.ACTIVE.equals(i.getStatus()))
                    .filter(i -> !QUARANTINE_ORG_ID.equals(i.getOrganizationId()))
                    .filter(i -> callerUserId != null && callerUserId.equals(i.getClaimedByUserId()))
                    .findFirst();
            if (opt.isEmpty() && gitHubAppConfig.isConfigured()) {
                try {
                    List<GitHubInstallationDetails> appInstalls = gitHubAppClient.listAppInstallations();
                    if (!appInstalls.isEmpty() && discoveryService != null) {
                        GitHubInstallationDetails first = appInstalls.get(0);
                        GitHubInstallation claimed = discoveryService.validateAndAssociate(callerUserId, effectiveWsId, organizationId, first.id());
                        opt = Optional.of(claimed);
                    }
                } catch (Exception e) {
                    log.debug("Auto-claim during getClaimedInstallation skipped: {}", e.getMessage());
                }
            }
        }

        return opt.filter(i -> InstallationStatus.ACTIVE.equals(i.getStatus()))
                .filter(i -> !QUARANTINE_ORG_ID.equals(i.getOrganizationId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active GitHub App installation found for this tenant. " +
                        "Install the ForgeAI GitHub App and claim the installation first."));
    }

    private Optional<GitHubInstallation> findAndBindPersonalInstallation(UUID callerUserId, UUID effectiveWsId) {
        if (identityServiceClient == null) return Optional.empty();
        try {
            var profileOpt = identityServiceClient.getUserGitHubProfile(callerUserId);
            if (profileOpt.isPresent() && profileOpt.get().connected()) {
                String login = profileOpt.get().githubLogin();
                Long ghId = profileOpt.get().githubUserId();
                List<GitHubInstallation> matching = new java.util.ArrayList<>();
                if (ghId != null) {
                    installationRepository.findByAccountId(ghId).ifPresent(matching::add);
                }
                if (login != null) {
                    matching.addAll(installationRepository.findByAccountLogin(login));
                }
                for (GitHubInstallation inst : matching) {
                    if (InstallationStatus.ACTIVE.equals(inst.getStatus())) {
                        inst.setWorkspaceId(effectiveWsId);
                        inst.setOrganizationId(null);
                        inst.setScope("PERSONAL");
                        inst.setClaimedByUserId(callerUserId);
                        inst.setUpdatedAt(Instant.now());
                        inst.setLastSyncedAt(Instant.now());
                        GitHubInstallation saved = installationRepository.save(inst);
                        log.info("[GitHub] Auto-associated personal installation {} to personal workspace {}",
                                inst.getInstallationId(), effectiveWsId);
                        return Optional.of(saved);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[GitHub] Could not auto-resolve personal installation for user {}: {}", callerUserId, e.getMessage());
        }
        return Optional.empty();
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
                .organizationId(repo.getOrganizationId())
                .repositoryFullName(repo.getFullName())
                .defaultBranch(repo.getDefaultBranch())
                .currentGithubHead(repo.getLatestObservedSha())
                .indexedHead(repo.getLatestIndexedSha())
                .latestObservedSha(repo.getLatestObservedSha())
                .latestIndexedSha(repo.getLatestIndexedSha())
                .syncStatus(status)
                .lastSyncedAt(repo.getLastSyncedAt())
                .lastError(repo.getLastError())
                .build();
    }

    @Transactional
    public com.forgeai.sourcecontrol.domain.model.RepositorySyncStatusDTO reconcileRepository(UUID organizationId, UUID repositoryId) {
        return reconcileRepository(organizationId, null, repositoryId, null);
    }

    @Transactional
    public com.forgeai.sourcecontrol.domain.model.RepositorySyncStatusDTO reconcileRepository(
            UUID organizationId, UUID workspaceId, UUID repositoryId) {
        return reconcileRepository(organizationId, workspaceId, repositoryId, null);
    }

    @Transactional
    public com.forgeai.sourcecontrol.domain.model.RepositorySyncStatusDTO reconcileRepository(
            UUID organizationId, UUID workspaceId, UUID repositoryId, String targetBranch) {
        requireTenantOrWorkspace(organizationId, workspaceId);
        Repository repo = getRepository(organizationId, workspaceId, repositoryId);

        String effectiveBranch = (targetBranch != null && !targetBranch.isBlank())
                ? targetBranch.trim()
                : (repo.getDefaultBranch() != null ? repo.getDefaultBranch() : "main");

        String currentHead = null;
        if (gitHubAppConfig.isConfigured() && repo.getInstallationId() != null && repo.getInstallationId() > 0) {
            try {
                // Find branch HEAD
                var branches = gitHubAppClient.listBranches(repo.getInstallationId(), repo.getFullName());
                for (var b : branches) {
                    if (effectiveBranch.equalsIgnoreCase(b.name())) {
                        currentHead = b.commitSha();
                        break;
                    }
                }
                if (currentHead == null) {
                    var commits = gitHubAppClient.listCommits(repo.getInstallationId(), repo.getFullName(), effectiveBranch, 1);
                    if (!commits.isEmpty()) {
                        currentHead = commits.get(0).sha();
                    }
                }
            } catch (Exception e) {
                log.warn("[GitHub] Could not query remote HEAD during reconciliation for repo {} (branch {}): {}",
                        repo.getFullName(), effectiveBranch, e.getMessage());
            }
        }

        if (currentHead != null && !currentHead.isBlank()) {
            repo.setLatestObservedSha(currentHead);
        } else if (repo.getLatestObservedSha() != null) {
            currentHead = repo.getLatestObservedSha();
        }

        if (currentHead != null && !currentHead.isBlank()) {
            final String finalHead = currentHead;
            boolean branchTargetExplicit = (targetBranch != null && !targetBranch.isBlank());
            if (!currentHead.equals(repo.getLatestIndexedSha()) || branchTargetExplicit) {
                repo.setSyncStatus(com.forgeai.sourcecontrol.domain.model.RepositorySyncStatus.SYNCING);
                repositoryRepository.save(repo);

                boolean syncSuccess = true;
                if (aiServiceClient != null) {
                    UUID tenantId = repo.getOrganizationId() != null ? repo.getOrganizationId() : repo.getWorkspaceId();
                    Optional<GitHubAppClient.GitHubCommitDetail> commitDetailOpt = Optional.empty();
                    if (gitHubAppConfig.isConfigured() && repo.getInstallationId() != null && repo.getInstallationId() > 0) {
                        try {
                            commitDetailOpt = gitHubAppClient.getCommitDetail(repo.getInstallationId(), repo.getFullName(), finalHead);
                        } catch (Exception ex) {
                            log.warn("[GitHub] Could not fetch commit detail for {}@{}: {}", repo.getFullName(), finalHead, ex.getMessage());
                        }
                    }

                    if (commitDetailOpt.isPresent()) {
                        var commitDetail = commitDetailOpt.get();
                        // Upsert Commit record into commitRepository
                        Commit c = commitRepository.findByRepositoryIdAndSha(repo.getId(), finalHead)
                                .orElseGet(() -> Commit.builder()
                                        .id(UUID.randomUUID())
                                        .organizationId(repo.getOrganizationId())
                                        .workspaceId(repo.getWorkspaceId())
                                        .repositoryId(repo.getId())
                                        .sha(finalHead)
                                        .createdAt(Instant.now())
                                        .build());
                        c.setMessage(commitDetail.message());
                        c.setAuthorName(commitDetail.authorName());
                        c.setAuthorEmail(commitDetail.authorEmail());
                        c.setCommittedAt(commitDetail.committedAt());
                        commitRepository.save(c);

                        List<AiServiceClient.ChangedFilePayload> changedFiles = new ArrayList<>();
                        int count = 0;
                        for (var file : commitDetail.files()) {
                            if (count >= 30) break;
                            String status = file.status() != null ? file.status() : "modified";
                            String content = "";
                            if (!"removed".equalsIgnoreCase(status)) {
                                content = gitHubAppClient.getFileContent(repo.getInstallationId(), repo.getFullName(), file.filename(), finalHead)
                                        .orElse(file.patch() != null ? file.patch() : "");
                            }
                            changedFiles.add(new AiServiceClient.ChangedFilePayload(file.filename(), status, content));
                            count++;
                        }

                        syncSuccess = aiServiceClient.syncPush(
                                tenantId,
                                repo.getId(),
                                finalHead,
                                "refs/heads/" + effectiveBranch,
                                changedFiles,
                                commitDetail.authorName(),
                                commitDetail.authorEmail(),
                                commitDetail.message()
                        );
                    } else {
                        syncSuccess = aiServiceClient.syncPush(
                                tenantId,
                                repo.getId(),
                                finalHead,
                                "refs/heads/" + effectiveBranch,
                                java.util.List.of()
                        );
                    }
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

        if (gitHubAppConfig.isConfigured() && repo.getInstallationId() != null && repo.getInstallationId() > 0 && workflowRunTestingServiceForwarder != null) {
            try {
                var runs = gitHubAppClient.listWorkflowRuns(repo.getInstallationId(), repo.getFullName(), 5);
                for (var r : runs) {
                    String scope = repo.getScope() != null ? repo.getScope() : (repo.getOrganizationId() != null ? "ORGANIZATION" : "PERSONAL");
                    UUID effectiveOrgId = "PERSONAL".equalsIgnoreCase(scope) ? null : repo.getOrganizationId();
                    var event = com.forgeai.sourcecontrol.domain.event.WorkflowRunReceivedEvent.builder()
                            .repositoryId(repo.getId())
                            .organizationId(effectiveOrgId)
                            .workspaceId(repo.getWorkspaceId())
                            .scope(scope)
                            .workflowRunId(r.id())
                            .workflowName(r.name())
                            .runNumber(r.runNumber())
                            .status(r.status())
                            .conclusion(r.conclusion())
                            .headBranch(r.headBranch())
                            .headSha(r.headSha())
                            .triggerEvent(r.event())
                            .runStartedAt(r.runStartedAt())
                            .completedAt(r.updatedAt())
                            .htmlUrl(r.htmlUrl())
                            .timestamp(Instant.now())
                            .build();
                    workflowRunTestingServiceForwarder.onEventEnvelope(
                            com.forgeai.sourcecontrol.domain.event.EventEnvelope.from(event, "reconcile-" + r.id(), "WorkflowRun", String.valueOf(r.id()))
                    );
                }
            } catch (Exception ex) {
                log.debug("[Reconcile] Could not query/forward workflow runs for repo {}: {}", repo.getFullName(), ex.getMessage());
            }
        }

        return getSyncStatus(organizationId, workspaceId, repositoryId);
    }

    @Transactional(readOnly = true)
    public String getFileContent(UUID organizationId, UUID workspaceId, UUID repositoryId, String path, String ref) {
        requireTenantOrWorkspace(organizationId, workspaceId);
        Repository repo = getRepository(organizationId, workspaceId, repositoryId);
        if (gitHubAppConfig.isConfigured() && repo.getInstallationId() != null && repo.getInstallationId() > 0) {
            String targetRef = (ref != null && !ref.isBlank()) ? ref : repo.getDefaultBranch();
            return gitHubAppClient.getFileContent(repo.getInstallationId(), repo.getFullName(), path, targetRef)
                    .orElseThrow(() -> new ResourceNotFoundException("File not found at path: " + path));
        }
        throw new ResourceNotFoundException("GitHub App not configured or repository not linked to installation");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void requireTenant(UUID organizationId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must be provided via tenant context");
        }
    }

    private boolean isPersonalWorkspace(UUID workspaceId) {
        if (workspaceId == null || workspaceRepository == null) {
            return false;
        }
        return workspaceRepository.findById(workspaceId)
                .map(ws -> "PERSONAL".equalsIgnoreCase(ws.getType()))
                .orElse(false);
    }

    private UUID resolveEffectiveWorkspaceId(UUID organizationId, UUID workspaceId) {
        if (organizationId != null && workspaceId != null) {
            if (organizationId.equals(workspaceId) && isPersonalWorkspace(workspaceId)) {
                return workspaceId;
            }
            if (workspaceRepository != null) {
                var wsOpt = workspaceRepository.findById(workspaceId);
                if (wsOpt.isPresent()) {
                    var ws = wsOpt.get();
                    if (ws.getOrganizationId() != null && !organizationId.equals(ws.getOrganizationId())) {
                        throw new IllegalArgumentException(
                                "Organization context " + organizationId + " does not match workspace context " + workspaceId);
                    }
                    if ("PERSONAL".equalsIgnoreCase(ws.getType())) {
                        if (!organizationId.equals(workspaceId)) {
                            throw new IllegalArgumentException(
                                    "Organization context " + organizationId + " does not match workspace context " + workspaceId);
                        }
                        return workspaceId;
                    }
                } else if (!organizationId.equals(workspaceId)) {
                    throw new IllegalArgumentException(
                            "Organization context " + organizationId + " does not match workspace context " + workspaceId);
                }
            } else if (!organizationId.equals(workspaceId)) {
                throw new IllegalArgumentException(
                        "Organization context " + organizationId + " does not match workspace context " + workspaceId);
            }
            return workspaceId;
        }
        if (workspaceId != null) {
            return workspaceId;
        }
        if (organizationId != null) {
            return organizationId;
        }
        if (com.forgeai.sourcecontrol.infrastructure.security.TenantContext.getUserId() != null && workspaceRepository != null) {
            var personalWs = workspaceRepository.findByOwnerUserIdAndType(
                    com.forgeai.sourcecontrol.infrastructure.security.TenantContext.getUserId(), "PERSONAL");
            if (personalWs.isPresent()) {
                return personalWs.get().getId();
            }
        }
        throw new IllegalArgumentException("Either organization ID or workspace ID must be provided");
    }

    private void requireTenantOrWorkspace(UUID organizationId, UUID workspaceId) {
        resolveEffectiveWorkspaceId(organizationId, workspaceId);
    }

    private void validateWorkspaceAccess(UUID workspaceId, UUID organizationId, UUID callerUserId) {
        if (workspaceId == null || workspaceRepository == null) {
            return;
        }
        workspaceRepository.findById(workspaceId).ifPresent(ws -> {
            if ("PERSONAL".equalsIgnoreCase(ws.getType())) {
                if (callerUserId != null && !callerUserId.equals(ws.getOwnerUserId())) {
                    throw new GitHubAccessDeniedException("Access denied: You do not own this personal workspace");
                }
            }
        });
    }
}

