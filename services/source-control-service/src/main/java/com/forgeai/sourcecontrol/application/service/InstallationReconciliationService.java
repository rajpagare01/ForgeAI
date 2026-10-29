package com.forgeai.sourcecontrol.application.service;

import com.forgeai.sourcecontrol.application.dto.ReconciliationReport;
import com.forgeai.sourcecontrol.domain.event.RepositoryConnectedEvent;
import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.model.InstallationStatus;
import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.domain.model.RepositorySyncStatus;
import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import com.forgeai.sourcecontrol.domain.repository.RepositoryRepository;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAccessDeniedException;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubInstallationDetails;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubRepositoryMetadata;
import com.forgeai.sourcecontrol.infrastructure.outbox.OutboxService;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataWorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for safe, idempotent reconciliation of GitHub installations and repository access.
 */
@Slf4j
@Service
public class InstallationReconciliationService {

    private final GitHubAppClient gitHubAppClient;
    private final GitHubInstallationRepository installationRepository;
    private final RepositoryRepository repositoryRepository;
    private final SpringDataWorkspaceRepository workspaceRepository;
    private final OutboxService outboxService;

    @Autowired
    public InstallationReconciliationService(
            GitHubAppClient gitHubAppClient,
            GitHubInstallationRepository installationRepository,
            RepositoryRepository repositoryRepository,
            @Autowired(required = false) SpringDataWorkspaceRepository workspaceRepository,
            OutboxService outboxService) {
        this.gitHubAppClient = gitHubAppClient;
        this.installationRepository = installationRepository;
        this.repositoryRepository = repositoryRepository;
        this.workspaceRepository = workspaceRepository;
        this.outboxService = outboxService;
    }

    /**
     * Idempotently reconciles an installation:
     * 1. Validates installation ownership and state with GitHub.
     * 2. Discovers accessible repositories.
     * 3. Compares with local database records.
     * 4. Adds or reactivates accessible repositories.
     * 5. Marks removed repositories as INACCESSIBLE (stops sync without deleting history).
     * 6. Updates installation telemetry and metadata.
     */
    @Transactional
    public ReconciliationReport reconcileInstallation(
            Long installationId,
            UUID workspaceId,
            UUID organizationId,
            UUID callerUserId) {

        if (installationId == null || installationId <= 0) {
            throw new IllegalArgumentException("Valid installationId is required for reconciliation");
        }

        UUID effectiveWsId = workspaceId != null ? workspaceId : organizationId;
        validateWorkspaceAccess(effectiveWsId, organizationId, callerUserId);

        GitHubInstallation installation = installationRepository.findByInstallationId(installationId)
                .orElseThrow(() -> new GitHubAccessDeniedException("Installation " + installationId + " not found."));

        // Verify tenant association
        if (organizationId != null && !organizationId.equals(installation.getOrganizationId())) {
            throw new GitHubAccessDeniedException("Installation " + installationId + " does not belong to this organization.");
        }
        if (effectiveWsId != null && !effectiveWsId.equals(installation.getWorkspaceId())) {
            throw new GitHubAccessDeniedException("Installation " + installationId + " does not belong to this workspace.");
        }

        // 1. Fetch latest installation metadata from GitHub
        GitHubInstallationDetails details = gitHubAppClient.getInstallationDetails(installationId);
        if (details.suspended()) {
            installation.setStatus(InstallationStatus.SUSPENDED);
        } else if (installation.getStatus() == InstallationStatus.SUSPENDED) {
            installation.setStatus(InstallationStatus.ACTIVE);
        }
        installation.setRepositorySelection(details.repositorySelection());
        installation.setAccountLogin(details.accountLogin());
        installation.setAccountId(details.accountId());
        installation.setLastSyncedAt(Instant.now());
        installationRepository.save(installation);

        // 2. Fetch accessible repositories from GitHub
        List<GitHubRepositoryMetadata> accessibleRepos = gitHubAppClient.listInstallationRepositories(installationId);
        Set<Long> accessibleRepoIds = accessibleRepos.stream()
                .map(GitHubRepositoryMetadata::id)
                .collect(Collectors.toSet());

        // 3. Fetch existing records in DB under this installation
        List<Repository> existingRepos = repositoryRepository.findAllByInstallationId(installationId);
        Map<Long, Repository> existingRepoMap = existingRepos.stream()
                .collect(Collectors.toMap(Repository::getGithubRepoId, r -> r, (r1, r2) -> r1));

        int addedCount = 0;
        int removedCount = 0;

        // 4. Reconcile newly accessible or modified repositories
        for (GitHubRepositoryMetadata ghRepo : accessibleRepos) {
            Repository repo = existingRepoMap.get(ghRepo.id());
            if (repo == null) {
                // New repository granted access
                repo = Repository.builder()
                        .organizationId(installation.getOrganizationId())
                        .workspaceId(installation.getWorkspaceId())
                        .scope(installation.getScope())
                        .installationId(installation.getInstallationId())
                        .githubRepoId(ghRepo.id())
                        .name(ghRepo.name())
                        .fullName(ghRepo.fullName())
                        .defaultBranch(ghRepo.defaultBranch())
                        .isPrivate(ghRepo.isPrivate())
                        .syncStatus(RepositorySyncStatus.SYNCED)
                        .lastSyncedAt(Instant.now())
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
                repo = repositoryRepository.save(repo);
                addedCount++;

                UUID repoId = repo.getId() != null ? repo.getId() : UUID.randomUUID();
                RepositoryConnectedEvent event = RepositoryConnectedEvent.builder()
                        .repositoryId(repoId)
                        .organizationId(repo.getOrganizationId())
                        .workspaceId(repo.getWorkspaceId())
                        .scope(repo.getScope() != null ? repo.getScope() : (repo.getOrganizationId() != null ? "ORGANIZATION" : "PERSONAL"))
                        .githubRepoId(repo.getGithubRepoId())
                        .fullName(repo.getFullName())
                        .defaultBranch(repo.getDefaultBranch())
                        .timestamp(Instant.now())
                        .build();
                outboxService.publish(event, UUID.randomUUID().toString(), "Repository", repoId.toString());
            } else {
                // Existing repo: update metadata and restore status if previously INACCESSIBLE
                repo.setName(ghRepo.name());
                repo.setFullName(ghRepo.fullName());
                repo.setDefaultBranch(ghRepo.defaultBranch());
                repo.setIsPrivate(ghRepo.isPrivate());
                if (repo.getSyncStatus() == RepositorySyncStatus.INACCESSIBLE || repo.getSyncStatus() == RepositorySyncStatus.DISCONNECTED) {
                    repo.setSyncStatus(RepositorySyncStatus.SYNCED);
                }
                repo.setLastSyncedAt(Instant.now());
                repo.setUpdatedAt(Instant.now());
                repositoryRepository.save(repo);
            }
        }

        // 5. Detect removed repositories: present in DB but no longer accessible via GitHub
        for (Repository existingRepo : existingRepos) {
            if (!accessibleRepoIds.contains(existingRepo.getGithubRepoId())) {
                if (existingRepo.getSyncStatus() != RepositorySyncStatus.INACCESSIBLE &&
                    existingRepo.getSyncStatus() != RepositorySyncStatus.DISCONNECTED) {
                    existingRepo.setSyncStatus(RepositorySyncStatus.INACCESSIBLE);
                    existingRepo.setUpdatedAt(Instant.now());
                    repositoryRepository.save(existingRepo);
                    removedCount++;
                    log.info("[Reconciliation] Repository {} (ID {}) marked INACCESSIBLE (access revoked on GitHub)",
                            existingRepo.getFullName(), existingRepo.getGithubRepoId());
                }
            }
        }

        log.info("[Reconciliation] Completed for installation {}: totalAccessible={}, newlyAdded={}, removed={}",
                installationId, accessibleRepos.size(), addedCount, removedCount);

        return new ReconciliationReport(
                installationId,
                installation.getWorkspaceId(),
                installation.getAccountLogin(),
                accessibleRepos.size(),
                addedCount,
                removedCount,
                Instant.now()
        );
    }

    private void validateWorkspaceAccess(UUID workspaceId, UUID organizationId, UUID callerUserId) {
        if (workspaceId == null || workspaceRepository == null) return;
        workspaceRepository.findById(workspaceId).ifPresent(ws -> {
            if ("PERSONAL".equalsIgnoreCase(ws.getType())) {
                if (callerUserId == null || !callerUserId.equals(ws.getOwnerUserId())) {
                    throw new GitHubAccessDeniedException("Access denied: You do not own this personal workspace");
                }
            }
        });
    }
}
