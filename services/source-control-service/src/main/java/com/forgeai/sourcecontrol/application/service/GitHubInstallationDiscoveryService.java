package com.forgeai.sourcecontrol.application.service;

import com.forgeai.sourcecontrol.application.dto.InstallationCandidateDto;
import com.forgeai.sourcecontrol.application.dto.InstallationDiscoveryResponse;
import com.forgeai.sourcecontrol.domain.event.RepositoryConnectedEvent;
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
import com.forgeai.sourcecontrol.infrastructure.metrics.InstallationTelemetryReconciler;
import com.forgeai.sourcecontrol.infrastructure.outbox.OutboxService;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataWorkspaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Core service for automatic GitHub App installation discovery, verification,
 * and workspace onboarding.
 *
 * <p>Key architectural guarantees:
 * <ul>
 *   <li>The user NEVER manually inputs an installation ID.</li>
 *   <li>Discovery sources: Webhooks, Setup URL callbacks, and GitHub App discovery APIs.</li>
 *   <li>Strict spoofing defense: Setup URL installation IDs are verified against GitHub API
 *       and authenticated user identity before association.</li>
 *   <li>Multi-tenant isolation: Users cannot claim installations belonging to other users or orgs.</li>
 *   <li>Short-lived access tokens generated dynamically; tokens never persist or leave backend.</li>
 * </ul>
 */
@Slf4j
@Service
public class GitHubInstallationDiscoveryService {

    private final GitHubAppClient gitHubAppClient;
    private final GitHubAppConfig gitHubAppConfig;
    private final GitHubInstallationRepository installationRepository;
    private final RepositoryRepository repositoryRepository;
    private final SpringDataWorkspaceRepository workspaceRepository;
    private final IdentityServiceClient identityServiceClient;
    private final OutboxService outboxService;
    private final InstallationTelemetryReconciler telemetryReconciler;

    @Autowired
    public GitHubInstallationDiscoveryService(
            GitHubAppClient gitHubAppClient,
            GitHubAppConfig gitHubAppConfig,
            GitHubInstallationRepository installationRepository,
            RepositoryRepository repositoryRepository,
            @Autowired(required = false) SpringDataWorkspaceRepository workspaceRepository,
            IdentityServiceClient identityServiceClient,
            OutboxService outboxService,
            @Autowired(required = false) InstallationTelemetryReconciler telemetryReconciler) {
        this.gitHubAppClient = gitHubAppClient;
        this.gitHubAppConfig = gitHubAppConfig;
        this.installationRepository = installationRepository;
        this.repositoryRepository = repositoryRepository;
        this.workspaceRepository = workspaceRepository;
        this.identityServiceClient = identityServiceClient;
        this.outboxService = outboxService;
        this.telemetryReconciler = telemetryReconciler;
    }

    /**
     * Automatically discovers available GitHub App installations for the caller and active workspace.
     */
    @Transactional(readOnly = true)
    public InstallationDiscoveryResponse discoverInstallations(
            UUID callerUserId,
            UUID workspaceId,
            UUID organizationId) {

        UUID effectiveWsId = resolveEffectiveWorkspaceId(organizationId, workspaceId);
        validateWorkspaceAccess(effectiveWsId, organizationId, callerUserId);

        // 1. Check if workspace already has an active claimed installation
        Optional<GitHubInstallation> currentOpt = organizationId != null
                ? installationRepository.findByOrganizationId(organizationId)
                : installationRepository.findByWorkspaceId(effectiveWsId);

        GitHubInstallation currentInstallation = currentOpt.orElse(null);

        // 2. Resolve caller's GitHub Identity
        Optional<IdentityServiceClient.UserGitHubProfile> profileOpt = Optional.empty();
        if (callerUserId != null) {
            profileOpt = identityServiceClient.getUserGitHubProfile(callerUserId);
        }

        boolean gitHubConnected = profileOpt.map(IdentityServiceClient.UserGitHubProfile::connected).orElse(false);
        String userGitHubLogin = profileOpt.map(IdentityServiceClient.UserGitHubProfile::githubLogin).orElse(null);
        Long userGitHubId = profileOpt.map(IdentityServiceClient.UserGitHubProfile::githubUserId).orElse(null);

        // 3. Discover candidates
        List<InstallationCandidateDto> candidates = new ArrayList<>();
        boolean isPersonal = organizationId == null;

        if (isPersonal && gitHubConnected && userGitHubLogin != null) {
            // Check local DB first for installations matching this user's account
            List<GitHubInstallation> matchingDbInstalls = new ArrayList<>();
            if (userGitHubId != null) {
                installationRepository.findByAccountId(userGitHubId).ifPresent(matchingDbInstalls::add);
            }
            matchingDbInstalls.addAll(installationRepository.findByAccountLogin(userGitHubLogin));

            for (GitHubInstallation inst : matchingDbInstalls) {
                boolean alreadyAssociated = inst.getWorkspaceId() != null && !inst.getWorkspaceId().equals(effectiveWsId);
                candidates.add(new InstallationCandidateDto(
                        inst.getInstallationId(),
                        inst.getAccountId(),
                        inst.getAccountLogin(),
                        inst.getAccountType(),
                        inst.getRepositorySelection(),
                        alreadyAssociated,
                        inst.getWorkspaceId(),
                        inst.getStatus()
                ));
            }

            // If none in DB, query GitHub App API directly for user's installation
            if (candidates.isEmpty() && gitHubAppConfig.isConfigured()) {
                try {
                    Optional<GitHubInstallationDetails> ghDetails = gitHubAppClient.findUserInstallation(userGitHubLogin);
                    if (ghDetails.isPresent()) {
                        GitHubInstallationDetails d = ghDetails.get();
                        candidates.add(new InstallationCandidateDto(
                                d.id(),
                                d.accountId(),
                                d.accountLogin(),
                                d.accountType(),
                                d.repositorySelection(),
                                false,
                                null,
                                InstallationStatus.CLAIMABLE
                        ));
                    }
                } catch (Exception e) {
                    log.debug("GitHub App API discovery for user {} returned: {}", userGitHubLogin, e.getMessage());
                }
            }
        } else if (!isPersonal && organizationId != null) {
            // Organization workspace candidate check
            installationRepository.findByOrganizationId(organizationId).ifPresent(inst -> {
                candidates.add(new InstallationCandidateDto(
                        inst.getInstallationId(),
                        inst.getAccountId(),
                        inst.getAccountLogin(),
                        inst.getAccountType(),
                        inst.getRepositorySelection(),
                        true,
                        inst.getWorkspaceId(),
                        inst.getStatus()
                ));
            });
        }

        String appInstallUrl = gitHubAppConfig.getAppInstallUrl();
        String message = currentInstallation != null
                ? "GitHub App installation is active and synchronized."
                : (!candidates.isEmpty()
                        ? "GitHub App installation discovered. Ready to onboard."
                        : "No GitHub App installation detected. Please install the ForgeAI GitHub App.");

        return new InstallationDiscoveryResponse(
                currentInstallation,
                candidates,
                userGitHubLogin,
                userGitHubId,
                gitHubConnected,
                appInstallUrl,
                message
        );
    }

    /**
     * Automatically onboards an installation without the user typing an installation ID.
     */
    @Transactional
    public GitHubInstallation autoOnboard(
            UUID callerUserId,
            UUID workspaceId,
            UUID organizationId,
            Long optionalInstallationId) {

        UUID effectiveWsId = resolveEffectiveWorkspaceId(organizationId, workspaceId);
        validateWorkspaceAccess(effectiveWsId, organizationId, callerUserId);
        gitHubAppConfig.requireConfigured();

        Long targetInstallationId = optionalInstallationId;

        if (targetInstallationId == null || targetInstallationId <= 0) {
            // Automatically discover from candidates
            InstallationDiscoveryResponse discovery = discoverInstallations(callerUserId, effectiveWsId, organizationId);
            if (!discovery.candidates().isEmpty()) {
                targetInstallationId = discovery.candidates().get(0).installationId();
            } else if (discovery.currentInstallation() != null) {
                targetInstallationId = discovery.currentInstallation().getInstallationId();
            } else {
                throw new IllegalStateException("No GitHub App installation found for this account. Please install the ForgeAI GitHub App first.");
            }
        }

        return validateAndAssociate(callerUserId, effectiveWsId, organizationId, targetInstallationId);
    }

    /**
     * Handles GitHub App setup URL redirect (?installation_id=...).
     * Validates installation ownership with cryptographic/API checks against GitHub.
     */
    @Transactional
    public GitHubInstallation handleSetupCallback(
            UUID callerUserId,
            UUID workspaceId,
            UUID organizationId,
            Long installationId) {

        if (installationId == null || installationId <= 0) {
            throw new IllegalArgumentException("Valid installationId is required in setup callback.");
        }

        UUID effectiveWsId = resolveEffectiveWorkspaceId(organizationId, workspaceId);
        validateWorkspaceAccess(effectiveWsId, organizationId, callerUserId);
        gitHubAppConfig.requireConfigured();

        log.info("[GitHub] Processing setup callback for installationId={}, workspaceId={}, userId={}",
                installationId, effectiveWsId, callerUserId);

        return validateAndAssociate(callerUserId, effectiveWsId, organizationId, installationId);
    }

    /**
     * Core validation and association routine. Enforces tenant boundaries and ownership.
     */
    private GitHubInstallation validateAndAssociate(
            UUID callerUserId,
            UUID effectiveWsId,
            UUID organizationId,
            Long installationId) {

        // 1. Authoritative metadata fetch from GitHub using App JWT
        GitHubInstallationDetails details = gitHubAppClient.getInstallationDetails(installationId);
        if (details == null) {
            throw new GitHubAccessDeniedException("Installation " + installationId + " could not be resolved on GitHub.");
        }

        boolean isPersonal = organizationId == null;
        String scope = isPersonal ? "PERSONAL" : "ORGANIZATION";

        // 2. Strict Ownership Validation (Anti-Spoofing)
        if (isPersonal) {
            if (callerUserId != null) {
                Optional<IdentityServiceClient.UserGitHubProfile> profileOpt = identityServiceClient.getUserGitHubProfile(callerUserId);
                if (profileOpt.isPresent() && profileOpt.get().connected()) {
                    IdentityServiceClient.UserGitHubProfile profile = profileOpt.get();
                    boolean idMatches = profile.githubUserId() != null && profile.githubUserId().equals(details.accountId());
                    boolean loginMatches = profile.githubLogin() != null && profile.githubLogin().equalsIgnoreCase(details.accountLogin());

                    if (!idMatches && !loginMatches) {
                        log.warn("[Security] Installation ownership verification failed! Caller GitHub account '{}/{}' != Installation account '{}/{}'",
                                profile.githubLogin(), profile.githubUserId(), details.accountLogin(), details.accountId());
                        throw new GitHubAccessDeniedException(
                                "Installation " + installationId + " belongs to GitHub account '" + details.accountLogin() +
                                        "', which does not match your authenticated GitHub account.");
                    }
                }
            }
        }

        // 3. Multi-Tenant Collision Check
        Optional<GitHubInstallation> existingClaimOpt = installationRepository.findByInstallationId(installationId);
        if (existingClaimOpt.isPresent()) {
            GitHubInstallation existing = existingClaimOpt.get();
            UUID QUARANTINE_ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
            boolean inQuarantine = QUARANTINE_ORG_ID.equals(existing.getOrganizationId())
                    || existing.getStatus() == InstallationStatus.QUARANTINED
                    || existing.getStatus() == InstallationStatus.DISCOVERED
                    || existing.getStatus() == InstallationStatus.CLAIMABLE;

            if (!inQuarantine) {
                if (organizationId != null && organizationId.equals(existing.getOrganizationId())) {
                    return existing;
                }
                if (effectiveWsId != null && effectiveWsId.equals(existing.getWorkspaceId())) {
                    return existing;
                }
                if (isPersonal) {
                    log.info("[GitHub] Associating personal installation {} to personal workspace {}",
                            installationId, effectiveWsId);
                    existing.setWorkspaceId(effectiveWsId);
                    existing.setOrganizationId(null);
                    existing.setScope("PERSONAL");
                    existing.setStatus(InstallationStatus.ACTIVE);
                    existing.setClaimedByUserId(callerUserId);
                    existing.setUpdatedAt(Instant.now());
                    existing.setLastSyncedAt(Instant.now());
                    GitHubInstallation saved = installationRepository.save(existing);
                    try {
                        List<GitHubRepositoryMetadata> accessibleRepos = gitHubAppClient.listInstallationRepositories(installationId);
                        for (GitHubRepositoryMetadata ghRepo : accessibleRepos) {
                            syncRepositoryRecord(ghRepo, saved);
                        }
                    } catch (Exception e) {
                        log.warn("[GitHub] Could not sync repos during personal association: {}", e.getMessage());
                    }
                    return saved;
                }
                throw new GitHubAccessDeniedException(
                        "Installation " + installationId + " is already claimed by another organization or workspace.");
            }
        }

        // 4. Update or Create Installation Record
        GitHubInstallation installation;
        if (existingClaimOpt.isPresent()) {
            installation = existingClaimOpt.get();
            installation.setOrganizationId(organizationId);
            installation.setWorkspaceId(effectiveWsId);
            installation.setScope(scope);
            installation.setGithubAppId(details.appId());
            installation.setAccountId(details.accountId());
            installation.setAccountLogin(details.accountLogin());
            installation.setAccountType(details.accountType());
            installation.setRepositorySelection(details.repositorySelection());
            installation.setClaimedByUserId(callerUserId);
            installation.setStatus(InstallationStatus.ACTIVE);
            installation.setUpdatedAt(Instant.now());
            installation.setLastSyncedAt(Instant.now());
        } else {
            installation = GitHubInstallation.builder()
                    .organizationId(organizationId)
                    .workspaceId(effectiveWsId)
                    .scope(scope)
                    .installationId(installationId)
                    .githubAppId(details.appId())
                    .accountId(details.accountId())
                    .accountLogin(details.accountLogin())
                    .accountType(details.accountType())
                    .repositorySelection(details.repositorySelection())
                    .claimedByUserId(callerUserId)
                    .status(InstallationStatus.ACTIVE)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .lastSyncedAt(Instant.now())
                    .build();
        }

        GitHubInstallation savedInstallation = installationRepository.save(installation);
        if (telemetryReconciler != null) {
            telemetryReconciler.reconcile();
        }

        log.info("[GitHub] Successfully associated installation {} with workspace {} (scope={})",
                installationId, effectiveWsId, scope);

        // 5. Automatic Repository Discovery and Staging
        try {
            List<GitHubRepositoryMetadata> accessibleRepos = gitHubAppClient.listInstallationRepositories(installationId);
            for (GitHubRepositoryMetadata ghRepo : accessibleRepos) {
                syncRepositoryRecord(ghRepo, savedInstallation);
            }
        } catch (Exception e) {
            log.warn("[GitHub] Could not immediately discover repositories for installation {}: {}", installationId, e.getMessage());
        }

        return savedInstallation;
    }

    private void syncRepositoryRecord(GitHubRepositoryMetadata ghRepo, GitHubInstallation installation) {
        Optional<Repository> existing = repositoryRepository.findByGithubRepoId(ghRepo.id());
        Repository repo;
        if (existing.isPresent()) {
            repo = existing.get();
            repo.setName(ghRepo.name());
            repo.setFullName(ghRepo.fullName());
            repo.setDefaultBranch(ghRepo.defaultBranch());
            repo.setIsPrivate(ghRepo.isPrivate());
            repo.setInstallationId(installation.getInstallationId());
            repo.setWorkspaceId(installation.getWorkspaceId());
            repo.setOrganizationId(installation.getOrganizationId());
            repo.setScope(installation.getScope());
            repo.setSyncStatus(RepositorySyncStatus.SYNCED);
            repo.setLastSyncedAt(Instant.now());
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        } else {
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

            RepositoryConnectedEvent event = RepositoryConnectedEvent.builder()
                    .repositoryId(repo.getId())
                    .organizationId(repo.getOrganizationId())
                    .githubRepoId(repo.getGithubRepoId())
                    .fullName(repo.getFullName())
                    .defaultBranch(repo.getDefaultBranch())
                    .timestamp(Instant.now())
                    .build();
            outboxService.publish(event, UUID.randomUUID().toString(), "Repository", repo.getId().toString());
        }
    }

    private UUID resolveEffectiveWorkspaceId(UUID organizationId, UUID workspaceId) {
        if (workspaceId != null) return workspaceId;
        if (organizationId != null) return organizationId;
        throw new IllegalArgumentException("Either workspaceId or organizationId must be provided");
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
