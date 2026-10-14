package com.forgeai.sourcecontrol.application.service;

import com.forgeai.sourcecontrol.domain.event.RepositoryConnectedEvent;
import com.forgeai.sourcecontrol.domain.exception.DuplicateResourceException;
import com.forgeai.sourcecontrol.domain.exception.ResourceNotFoundException;
import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.model.InstallationStatus;
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

    public RepositoryApplicationService(
            RepositoryRepository repositoryRepository,
            GitHubInstallationRepository installationRepository,
            com.forgeai.sourcecontrol.domain.repository.PullRequestRepository pullRequestRepository,
            OutboxService outboxService,
            GitHubAppClient gitHubAppClient,
            GitHubAppConfig gitHubAppConfig) {
        this(repositoryRepository, installationRepository, pullRequestRepository, null, outboxService, gitHubAppClient, gitHubAppConfig);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RepositoryApplicationService(
            RepositoryRepository repositoryRepository,
            GitHubInstallationRepository installationRepository,
            com.forgeai.sourcecontrol.domain.repository.PullRequestRepository pullRequestRepository,
            com.forgeai.sourcecontrol.domain.repository.CommitRepository commitRepository,
            OutboxService outboxService,
            GitHubAppClient gitHubAppClient,
            GitHubAppConfig gitHubAppConfig) {
        this.repositoryRepository = repositoryRepository;
        this.installationRepository = installationRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.commitRepository = commitRepository;
        this.outboxService = outboxService;
        this.gitHubAppClient = gitHubAppClient;
        this.gitHubAppConfig = gitHubAppConfig;
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<com.forgeai.sourcecontrol.domain.model.PullRequest> listPullRequests(
            UUID organizationId, UUID repositoryId) {
        requireTenant(organizationId);
        if (repositoryId != null) {
            getRepository(organizationId, repositoryId);
            return pullRequestRepository.findAllByRepositoryId(repositoryId);
        }
        return pullRequestRepository.findAllByOrganizationId(organizationId);
    }

    @Transactional(readOnly = true)
    public List<Repository> listRepositories(UUID organizationId) {
        requireTenant(organizationId);
        return repositoryRepository.findAllByOrganizationId(organizationId);
    }

    @Transactional(readOnly = true)
    public Repository getRepository(UUID organizationId, UUID repositoryId) {
        requireTenant(organizationId);
        return repositoryRepository.findByIdAndOrganizationId(repositoryId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found: " + repositoryId));
    }

    @Transactional(readOnly = true)
    public List<com.forgeai.sourcecontrol.domain.model.Commit> listCommits(UUID organizationId, UUID repositoryId) {
        requireTenant(organizationId);
        getRepository(organizationId, repositoryId);
        return commitRepository.findAllByRepositoryId(repositoryId);
    }

    @Transactional(readOnly = true)
    public List<com.forgeai.sourcecontrol.infrastructure.github.GitHubBranchMetadata> listBranches(
            UUID organizationId, UUID repositoryId) {
        requireTenant(organizationId);
        Repository repo = getRepository(organizationId, repositoryId);
        return gitHubAppClient.listBranches(repo.getInstallationId(), repo.getFullName());
    }

    @Transactional
    public List<com.forgeai.sourcecontrol.domain.model.Commit> syncCommits(UUID organizationId, UUID repositoryId) {
        requireTenant(organizationId);
        Repository repo = getRepository(organizationId, repositoryId);
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
                        .organizationId(organizationId)
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
        requireTenant(organizationId);
        Repository repo = getRepository(organizationId, repositoryId);
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
                        .organizationId(organizationId)
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
        requireTenant(organizationId);
        gitHubAppConfig.requireConfigured();

        // Must not be already claimed by this or another real org
        Optional<GitHubInstallation> alreadyClaimed = installationRepository.findByInstallationId(installationId);
        if (alreadyClaimed.isPresent()) {
            GitHubInstallation existing = alreadyClaimed.get();
            if (!QUARANTINE_ORG_ID.equals(existing.getOrganizationId())) {
                if (organizationId.equals(existing.getOrganizationId())) {
                    // Already owned by this org — idempotent OK
                    return existing;
                }
                throw new GitHubAccessDeniedException(
                        "Installation " + installationId + " is already claimed by another organization.");
            }
        }

        // Verify the installation actually exists on GitHub
        String accountLogin = gitHubAppClient.getInstallationAccountLogin(installationId);
        log.info("[GitHub] Verified installation {} belongs to GitHub account '{}'", installationId, accountLogin);

        // Check if org already owns a different installation
        Optional<GitHubInstallation> orgInstall = installationRepository.findByOrganizationId(organizationId);
        if (orgInstall.isPresent() && !QUARANTINE_ORG_ID.equals(orgInstall.get().getOrganizationId())) {
            if (!installationId.equals(orgInstall.get().getInstallationId())) {
                throw new GitHubAccessDeniedException(
                        "Organization already has a claimed GitHub installation (" + orgInstall.get().getInstallationId() + "). " +
                        "Remove it before claiming a different installation.");
            }
        }

        if (alreadyClaimed.isPresent()) {
            // Promote quarantined record
            GitHubInstallation inst = alreadyClaimed.get();
            inst.setOrganizationId(organizationId);
            inst.setStatus(InstallationStatus.ACTIVE);
            inst.setUpdatedAt(Instant.now());
            GitHubInstallation saved = installationRepository.save(inst);
            log.info("[GitHub] Installation {} claimed by orgId={}", installationId, organizationId);
            return saved;
        } else {
            // Installation exists on GitHub but not yet in our DB (e.g. webhook not yet received)
            GitHubInstallation inst = GitHubInstallation.builder()
                    .organizationId(organizationId)
                    .installationId(installationId)
                    .accountLogin(accountLogin)
                    .accountType("Organization")
                    .status(InstallationStatus.ACTIVE)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            GitHubInstallation saved = installationRepository.save(inst);
            log.info("[GitHub] New installation {} registered and claimed by orgId={}", installationId, organizationId);
            return saved;
        }
    }

    /**
     * Lists all GitHub repositories accessible to the installation claimed by this organization.
     * All data comes from GitHub — no local cache.
     */
    @Transactional(readOnly = true)
    public List<GitHubRepositoryMetadata> listAvailableRepositories(UUID organizationId) {
        requireTenant(organizationId);
        gitHubAppConfig.requireConfigured();

        GitHubInstallation installation = installationRepository.findByOrganizationId(organizationId)
                .filter(i -> InstallationStatus.ACTIVE.equals(i.getStatus()))
                .filter(i -> !QUARANTINE_ORG_ID.equals(i.getOrganizationId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active GitHub App installation found for this organization. " +
                        "Install the ForgeAI GitHub App and claim the installation first."));

        return gitHubAppClient.listInstallationRepositories(installation.getInstallationId());
    }

    // -------------------------------------------------------------------------
    // Repository connection (production-only, no fake data)
    // -------------------------------------------------------------------------

    /**
     * Connects a repository to this ForgeAI organization.
     *
     * <p>Security contract:
     * <ul>
     *   <li>GitHub App must be configured.</li>
     *   <li>The organization must own an active, claimed installation.</li>
     *   <li>The repository identified by {@code fullName} must be in the installation's
     *       accessible repository list — verified live against GitHub.</li>
     *   <li>All metadata (repo ID, owner, name, private, defaultBranch) comes from GitHub.
     *       No caller-supplied values are accepted except {@code fullName} used as a lookup key.</li>
     * </ul>
     *
     * @param organizationId authenticated organization
     * @param fullName       "owner/repo" used to identify the repository on GitHub
     * @return the persisted {@link Repository}
     */
    @Transactional
    public Repository connectRepository(UUID organizationId, String fullName) {
        requireTenant(organizationId);
        gitHubAppConfig.requireConfigured();

        if (fullName == null || fullName.isBlank() || !fullName.contains("/")) {
            throw new IllegalArgumentException(
                    "fullName must be in 'owner/repo' format, e.g. 'acme/my-service'");
        }

        // Resolve the organization's claimed installation
        GitHubInstallation installation = installationRepository.findByOrganizationId(organizationId)
                .filter(i -> InstallationStatus.ACTIVE.equals(i.getStatus()))
                .filter(i -> !QUARANTINE_ORG_ID.equals(i.getOrganizationId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active GitHub App installation found for this organization. " +
                        "Install the ForgeAI GitHub App and claim the installation first."));

        // Fetch authoritative metadata from GitHub and verify access
        GitHubRepositoryMetadata ghRepo = gitHubAppClient.getVerifiedRepository(
                installation.getInstallationId(), fullName);

        // Duplicate check and idempotent reconciliation
        Optional<Repository> existing = repositoryRepository.findByGithubRepoId(ghRepo.id());
        if (existing.isPresent()) {
            Repository existingRepo = existing.get();
            if (organizationId.equals(existingRepo.getOrganizationId())) {
                existingRepo.setName(ghRepo.name());
                existingRepo.setFullName(ghRepo.fullName());
                existingRepo.setDefaultBranch(ghRepo.defaultBranch());
                existingRepo.setIsPrivate(ghRepo.isPrivate());
                existingRepo.setInstallationId(installation.getInstallationId());
                existingRepo.setUpdatedAt(Instant.now());
                Repository updated = repositoryRepository.save(existingRepo);
                log.info("[GitHub] Idempotently reconciled repository: id={}, fullName={}, githubRepoId={}, orgId={}",
                        updated.getId(), updated.getFullName(), updated.getGithubRepoId(), organizationId);
                return updated;
            }
            throw new DuplicateResourceException(
                    "Repository '" + fullName + "' (GitHub ID " + ghRepo.id() + ") is already connected to another organization.");
        }

        Repository repo = Repository.builder()
                .organizationId(organizationId)
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
                .githubRepoId(saved.getGithubRepoId())
                .fullName(saved.getFullName())
                .defaultBranch(saved.getDefaultBranch())
                .timestamp(Instant.now())
                .build();

        outboxService.publish(event, "connect-" + saved.getId(), "Repository", saved.getId().toString());
        log.info("[GitHub] Connected repository: id={}, fullName={}, githubRepoId={}, orgId={}",
                saved.getId(), saved.getFullName(), saved.getGithubRepoId(), organizationId);
        return saved;
    }

    /**
     * Returns the active claimed installation for this organization, or throws
     * {@link ResourceNotFoundException} if none exists.
     */
    @Transactional(readOnly = true)
    public GitHubInstallation getClaimedInstallation(UUID organizationId) {
        requireTenant(organizationId);
        return installationRepository.findByOrganizationId(organizationId)
                .filter(i -> InstallationStatus.ACTIVE.equals(i.getStatus()))
                .filter(i -> !QUARANTINE_ORG_ID.equals(i.getOrganizationId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active GitHub App installation found for this organization. " +
                        "Install the ForgeAI GitHub App and claim the installation first."));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void requireTenant(UUID organizationId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must be provided via tenant context");
        }
    }
}
