package com.forgeai.sourcecontrol.application.service;

import com.forgeai.sourcecontrol.application.dto.GitHubConnectionResponse;
import com.forgeai.sourcecontrol.application.dto.InstallationDiscoveryResponse;
import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.model.InstallationStatus;
import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import com.forgeai.sourcecontrol.domain.repository.RepositoryRepository;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubRepositoryMetadata;
import com.forgeai.sourcecontrol.infrastructure.identity.IdentityServiceClient;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataWorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Unified orchestration service for the single-click "Connect GitHub" onboarding experience.
 * Seamlessly resolves GitHub OAuth identity, GitHub App installation status,
 * workspace-level tenant ownership, and live repository discovery.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubConnectionService {

    private final GitHubAppConfig gitHubAppConfig;
    private final GitHubAppClient gitHubAppClient;
    private final GitHubInstallationRepository installationRepository;
    private final RepositoryRepository repositoryRepository;
    private final SpringDataWorkspaceRepository workspaceRepository;
    private final IdentityServiceClient identityServiceClient;
    private final GitHubInstallationDiscoveryService discoveryService;
    private final InstallationReconciliationService reconciliationService;

    // Intentionally non-transactional: discoveryService.autoOnboard runs its own TX inside try/catch,
    // so an expected rejection must not mark a shared outer TX as rollback-only (UnexpectedRollbackException).
    public GitHubConnectionResponse getConnectionStatus(
            UUID callerUserId,
            UUID workspaceId,
            UUID organizationId) {

        UUID effectiveWsId = resolveEffectiveWorkspaceId(organizationId, workspaceId);
        boolean isPersonal = (organizationId == null);
        String workspaceScope = isPersonal ? "PERSONAL" : "ORGANIZATION";

        if (isPersonal) {
            if (callerUserId == null) {
                throw new com.forgeai.sourcecontrol.infrastructure.github.GitHubAccessDeniedException(
                        "Authenticated caller user identity is required for personal workspace connection status");
            }
            if (effectiveWsId != null && workspaceRepository != null) {
                workspaceRepository.findById(effectiveWsId).ifPresent(ws -> {
                    if ("PERSONAL".equalsIgnoreCase(ws.getType()) && !callerUserId.equals(ws.getOwnerUserId())) {
                        throw new com.forgeai.sourcecontrol.infrastructure.github.GitHubAccessDeniedException(
                                "Access denied: You do not own this personal workspace");
                    }
                });
            }
        }

        // 1. Resolve linked GitHub OAuth profile from Identity Service
        Optional<IdentityServiceClient.UserGitHubProfile> profileOpt = Optional.empty();
        if (callerUserId != null) {
            profileOpt = identityServiceClient.getUserGitHubProfile(callerUserId);
        }

        boolean gitHubConnected = profileOpt.map(IdentityServiceClient.UserGitHubProfile::connected).orElse(false);
        Long userGitHubId = profileOpt.map(IdentityServiceClient.UserGitHubProfile::githubUserId).orElse(null);
        String userGitHubLogin = profileOpt.map(IdentityServiceClient.UserGitHubProfile::githubLogin).orElse(null);
        String userGitHubName = profileOpt.map(IdentityServiceClient.UserGitHubProfile::name).orElse(null);
        String userGitHubEmail = profileOpt.map(IdentityServiceClient.UserGitHubProfile::email).orElse(null);
        String userGitHubAvatarUrl = profileOpt.map(IdentityServiceClient.UserGitHubProfile::avatarUrl).orElse(null);

        String appInstallUrl = gitHubAppConfig.getAppInstallUrl();
        String appSlug = gitHubAppConfig.getAppSlug();

        // 2. Discover or resolve active GitHub App installation for this workspace
        Optional<GitHubInstallation> instOpt = organizationId != null
                ? installationRepository.findByOrganizationId(organizationId)
                : (effectiveWsId != null ? installationRepository.findByWorkspaceId(effectiveWsId) : Optional.empty());

        GitHubInstallation activeInst = instOpt.orElse(null);

        // Fallback for personal workspace: If active installation exists in DB, recover it only if it matches caller
        if (activeInst == null && isPersonal) {
            for (GitHubInstallation inst : installationRepository.findAll()) {
                if (inst.getStatus() == InstallationStatus.ACTIVE && inst.getOrganizationId() == null) {
                    boolean matchesCaller = (callerUserId != null && callerUserId.equals(inst.getClaimedByUserId()))
                            || (userGitHubId != null && userGitHubId.equals(inst.getAccountId()))
                            || (userGitHubLogin != null && userGitHubLogin.equalsIgnoreCase(inst.getAccountLogin()))
                            || (effectiveWsId != null && effectiveWsId.equals(inst.getWorkspaceId()));
                    if (matchesCaller) {
                        activeInst = inst;
                        break;
                    }
                }
            }
        }

        // If not directly associated in this workspace, attempt automatic seamless binding
        if (activeInst == null && callerUserId != null) {
            InstallationDiscoveryResponse discovery = discoveryService.discoverInstallations(callerUserId, effectiveWsId, organizationId);
            if (discovery.currentInstallation() != null) {
                activeInst = discovery.currentInstallation();
            } else if (discovery.candidates() != null && !discovery.candidates().isEmpty()) {
                try {
                    activeInst = discoveryService.autoOnboard(callerUserId, effectiveWsId, organizationId, discovery.candidates().get(0).installationId());
                } catch (Exception ex) {
                    log.warn("[GitHub Connection] Candidate auto-onboarding deferred: {}", ex.getMessage());
                }
            }
        }

        // If active installation exists and is verified active, workspace is already connected to GitHub!
        if (activeInst != null && activeInst.getStatus() == InstallationStatus.ACTIVE) {
            gitHubConnected = true;
            if (userGitHubLogin == null || userGitHubLogin.isBlank()) {
                userGitHubLogin = activeInst.getAccountLogin();
            }
            if (userGitHubId == null) {
                userGitHubId = activeInst.getAccountId();
            }
        }

        // If user hasn't authenticated with GitHub OAuth AND no active app installation exists
        if (!gitHubConnected && activeInst == null) {
            return new GitHubConnectionResponse(
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "NOT_INSTALLED",
                    true,
                    null,
                    null,
                    appInstallUrl,
                    appSlug,
                    effectiveWsId,
                    workspaceScope,
                    0,
                    0,
                    "DISCONNECTED",
                    "GitHub account is not connected. Authenticate with GitHub to continue."
            );
        }

        if (activeInst != null && activeInst.getStatus() == InstallationStatus.ACTIVE) {
            int connectedCount = effectiveWsId != null
                    ? repositoryRepository.findAllByWorkspaceId(effectiveWsId).size()
                    : (organizationId != null ? repositoryRepository.findAllByOrganizationId(organizationId).size() : 0);

            int availableCount = connectedCount;
            try {
                List<GitHubRepositoryMetadata> liveRepos = gitHubAppClient.listInstallationRepositories(activeInst.getInstallationId());
                availableCount = liveRepos.size();
            } catch (Exception e) {
                log.debug("[GitHub Connection] Live repository count fallback to connected: {}", e.getMessage());
            }

            return new GitHubConnectionResponse(
                    true,
                    userGitHubId,
                    userGitHubLogin,
                    userGitHubName,
                    userGitHubEmail,
                    userGitHubAvatarUrl,
                    activeInst.getStatus().name(),
                    false,
                    activeInst.getInstallationId(),
                    activeInst.getRepositorySelection(),
                    appInstallUrl,
                    appSlug,
                    effectiveWsId,
                    workspaceScope,
                    availableCount,
                    connectedCount,
                    "REPOSITORIES_READY",
                    "GitHub account and ForgeAI App connected. Repositories ready."
            );
        }

        // GitHub identity is linked, but GitHub App is not yet installed on this account
        return new GitHubConnectionResponse(
                true,
                userGitHubId,
                userGitHubLogin,
                userGitHubName,
                userGitHubEmail,
                userGitHubAvatarUrl,
                "NOT_INSTALLED",
                true,
                null,
                null,
                appInstallUrl,
                appSlug,
                effectiveWsId,
                workspaceScope,
                0,
                0,
                "INSTALLATION_REQUIRED",
                "ForgeAI GitHub App needs repository access. Please approve the app installation."
        );
    }

    @Transactional
    public GitHubConnectionResponse connectGitHub(
            UUID callerUserId,
            UUID workspaceId,
            UUID organizationId,
            Long hintInstallationId) {

        UUID effectiveWsId = resolveEffectiveWorkspaceId(organizationId, workspaceId);

        // Auto-onboard installation with strict ownership validation
        GitHubInstallation onboarded = discoveryService.autoOnboard(callerUserId, effectiveWsId, organizationId, hintInstallationId);

        // Reconcile repositories live from GitHub
        try {
            reconciliationService.reconcileInstallation(onboarded.getInstallationId(), effectiveWsId, organizationId, callerUserId);
        } catch (Exception ex) {
            log.warn("[GitHub Connection] Reconciliation warning during connect: {}", ex.getMessage());
        }

        return getConnectionStatus(callerUserId, workspaceId, organizationId);
    }

    @Transactional
    public GitHubConnectionResponse reconcileGitHub(
            UUID callerUserId,
            UUID workspaceId,
            UUID organizationId) {

        UUID effectiveWsId = resolveEffectiveWorkspaceId(organizationId, workspaceId);

        Optional<GitHubInstallation> instOpt = organizationId != null
                ? installationRepository.findByOrganizationId(organizationId)
                : (effectiveWsId != null ? installationRepository.findByWorkspaceId(effectiveWsId) : Optional.empty());

        if (instOpt.isPresent()) {
            reconciliationService.reconcileInstallation(instOpt.get().getInstallationId(), effectiveWsId, organizationId, callerUserId);
        }

        return getConnectionStatus(callerUserId, workspaceId, organizationId);
    }

    private UUID resolveEffectiveWorkspaceId(UUID organizationId, UUID workspaceId) {
        if (workspaceId != null) {
            if (organizationId != null && workspaceRepository != null) {
                workspaceRepository.findById(workspaceId).ifPresent(ws -> {
                    if (ws.getOrganizationId() != null && !organizationId.equals(ws.getOrganizationId())) {
                        throw new IllegalArgumentException(
                                "Organization context " + organizationId + " does not match workspace org " + ws.getOrganizationId());
                    }
                });
            }
            if (organizationId != null && !isPersonalWorkspace(workspaceId)) {
                return organizationId;
            }
            return workspaceId;
        }
        if (organizationId != null) {
            return organizationId;
        }
        return null;
    }

    private boolean isPersonalWorkspace(UUID workspaceId) {
        if (workspaceId == null || workspaceRepository == null) return false;
        return workspaceRepository.findById(workspaceId)
                .map(ws -> "PERSONAL".equalsIgnoreCase(ws.getType()))
                .orElse(false);
    }
}

