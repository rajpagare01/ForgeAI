package com.forgeai.sourcecontrol.application.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.forgeai.sourcecontrol.domain.event.RepositoryConnectedEvent;
import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.model.InstallationStatus;
import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.domain.model.RepositorySyncStatus;
import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import com.forgeai.sourcecontrol.domain.repository.RepositoryRepository;
import com.forgeai.sourcecontrol.infrastructure.metrics.InstallationTelemetryReconciler;
import com.forgeai.sourcecontrol.infrastructure.outbox.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class InstallationEventHandler {

    private final GitHubInstallationRepository installationRepository;
    private final RepositoryRepository repositoryRepository;
    private final OutboxService outboxService;
    private final InstallationTelemetryReconciler telemetryReconciler;

    public InstallationEventHandler(GitHubInstallationRepository installationRepository,
                                  RepositoryRepository repositoryRepository,
                                  OutboxService outboxService) {
        this(installationRepository, repositoryRepository, outboxService, null);
    }

    @Autowired
    public InstallationEventHandler(GitHubInstallationRepository installationRepository,
                                  RepositoryRepository repositoryRepository,
                                  OutboxService outboxService,
                                  @Autowired(required = false) InstallationTelemetryReconciler telemetryReconciler) {
        this.installationRepository = installationRepository;
        this.repositoryRepository = repositoryRepository;
        this.outboxService = outboxService;
        this.telemetryReconciler = telemetryReconciler;
    }

    @Transactional
    public void handle(JsonNode rootNode, String deliveryId) {
        String action = rootNode.path("action").asText();
        JsonNode installationNode = rootNode.path("installation");
        if (installationNode.isMissingNode() || installationNode.isNull()) {
            return;
        }

        Long installationId = installationNode.path("id").asLong();
        Long appId = installationNode.hasNonNull("app_id") ? installationNode.path("app_id").asLong() : null;
        JsonNode accountNode = installationNode.path("account");
        Long accountId = accountNode.hasNonNull("id") ? accountNode.path("id").asLong() : null;
        String accountLogin = accountNode.path("login").asText("unknown");
        String accountType = accountNode.path("type").asText("Organization");
        String repoSelection = installationNode.path("repository_selection").asText("all").toUpperCase();

        // Find existing or create new installation
        Optional<GitHubInstallation> existingOpt = installationRepository.findByInstallationId(installationId);
        GitHubInstallation installation;
        if (existingOpt.isPresent()) {
            installation = existingOpt.get();
            if (appId != null) installation.setGithubAppId(appId);
            if (accountId != null) installation.setAccountId(accountId);
            installation.setAccountLogin(accountLogin);
            installation.setAccountType(accountType);
            installation.setRepositorySelection(repoSelection);
            installation.setUpdatedAt(Instant.now());

            if ("deleted".equalsIgnoreCase(action)) {
                installation.setStatus(InstallationStatus.DELETED);
                // Mark all associated repositories as disconnected/inaccessible while preserving history
                List<Repository> attached = repositoryRepository.findAllByInstallationId(installationId);
                for (Repository r : attached) {
                    r.setSyncStatus(RepositorySyncStatus.DISCONNECTED);
                    r.setUpdatedAt(Instant.now());
                    repositoryRepository.save(r);
                }
                log.info("[GitHub] Installation {} deleted; marked {} repositories as DISCONNECTED", installationId, attached.size());
            } else if ("suspend".equalsIgnoreCase(action)) {
                installation.setStatus(InstallationStatus.SUSPENDED);
            } else if ("unsuspend".equalsIgnoreCase(action) || "created".equalsIgnoreCase(action)) {
                installation.setStatus(InstallationStatus.ACTIVE);
            }
            installation = installationRepository.save(installation);
            if (telemetryReconciler != null) telemetryReconciler.reconcile();
        } else {
            UUID orgId = null;
            if (rootNode.hasNonNull("organization_id")) {
                orgId = UUID.fromString(rootNode.path("organization_id").asText());
            } else if (rootNode.hasNonNull("org_id")) {
                orgId = UUID.fromString(rootNode.path("org_id").asText());
            } else if (rootNode.path("client_payload").hasNonNull("organization_id")) {
                orgId = UUID.fromString(rootNode.path("client_payload").path("organization_id").asText());
            }

            if (orgId == null) {
                // Unknown installation: quarantine with SUSPENDED status awaiting explicit onboarding
                UUID QUARANTINE_ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
                log.warn("Unknown GitHub installation {}: no ForgeAI organization mapping found. Quarantining with SUSPENDED status under quarantine tenant {}. Requires explicit onboarding.", installationId, QUARANTINE_ORG_ID);
                installation = GitHubInstallation.builder()
                        .organizationId(QUARANTINE_ORG_ID)
                        .workspaceId(QUARANTINE_ORG_ID)
                        .scope("ORGANIZATION")
                        .installationId(installationId)
                        .githubAppId(appId)
                        .accountId(accountId)
                        .accountLogin(accountLogin)
                        .accountType(accountType)
                        .repositorySelection(repoSelection)
                        .status(InstallationStatus.SUSPENDED)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
                installationRepository.save(installation);
                if (telemetryReconciler != null) telemetryReconciler.reconcile();
                return; // Do not process attached repositories for quarantined installation
            }

            installation = GitHubInstallation.builder()
                    .organizationId(orgId)
                    .workspaceId(orgId)
                    .scope("ORGANIZATION")
                    .installationId(installationId)
                    .githubAppId(appId)
                    .accountId(accountId)
                    .accountLogin(accountLogin)
                    .accountType(accountType)
                    .repositorySelection(repoSelection)
                    .status(InstallationStatus.ACTIVE)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            installation = installationRepository.save(installation);
            if (telemetryReconciler != null) telemetryReconciler.reconcile();
        }

        // Handle repositories attached
        JsonNode reposNode = rootNode.path("repositories");
        if (reposNode.isArray()) {
            for (JsonNode repoNode : reposNode) {
                processRepositoryNode(repoNode, installation, deliveryId);
            }
        }
        JsonNode reposAddedNode = rootNode.path("repositories_added");
        if (reposAddedNode.isArray()) {
            for (JsonNode repoNode : reposAddedNode) {
                processRepositoryNode(repoNode, installation, deliveryId);
            }
        }

        // Handle repositories removed (e.g. user revoked access to specific repo in GitHub App settings)
        JsonNode reposRemovedNode = rootNode.path("repositories_removed");
        if (reposRemovedNode.isArray()) {
            for (JsonNode repoNode : reposRemovedNode) {
                Long githubRepoId = repoNode.path("id").asLong();
                repositoryRepository.findByGithubRepoId(githubRepoId).ifPresent(repo -> {
                    repo.setSyncStatus(RepositorySyncStatus.INACCESSIBLE);
                    repo.setUpdatedAt(Instant.now());
                    repositoryRepository.save(repo);
                    log.info("[GitHub] Repository {} (ID {}) marked INACCESSIBLE following access revocation in GitHub App",
                            repo.getFullName(), githubRepoId);
                });
            }
        }
    }

    private void processRepositoryNode(JsonNode repoNode, GitHubInstallation installation, String deliveryId) {
        Long githubRepoId = repoNode.path("id").asLong();
        String name = repoNode.path("name").asText();
        String fullName = repoNode.path("full_name").asText();
        boolean isPrivate = repoNode.path("private").asBoolean(true);

        Optional<Repository> existingRepo = repositoryRepository.findByGithubRepoId(githubRepoId);
        Repository repo;
        if (existingRepo.isPresent()) {
            repo = existingRepo.get();
            repo.setName(name);
            repo.setFullName(fullName);
            repo.setIsPrivate(isPrivate);
            if (repo.getSyncStatus() == RepositorySyncStatus.INACCESSIBLE || repo.getSyncStatus() == RepositorySyncStatus.DISCONNECTED) {
                repo.setSyncStatus(RepositorySyncStatus.SYNCED);
            }
            repo.setUpdatedAt(Instant.now());
            repo = repositoryRepository.save(repo);
        } else {
            repo = Repository.builder()
                    .organizationId(installation.getOrganizationId())
                    .workspaceId(installation.getWorkspaceId())
                    .scope(installation.getScope())
                    .installationId(installation.getInstallationId())
                    .githubRepoId(githubRepoId)
                    .name(name)
                    .fullName(fullName)
                    .defaultBranch("main")
                    .isPrivate(isPrivate)
                    .syncStatus(RepositorySyncStatus.SYNCED)
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
            outboxService.publish(event, deliveryId, "Repository", repo.getId().toString());
        }
    }
}
