package com.forgeai.sourcecontrol.application.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.forgeai.sourcecontrol.domain.event.RepositoryConnectedEvent;
import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.model.InstallationStatus;
import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import com.forgeai.sourcecontrol.domain.repository.RepositoryRepository;
import com.forgeai.sourcecontrol.infrastructure.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class InstallationEventHandler {

    private final GitHubInstallationRepository installationRepository;
    private final RepositoryRepository repositoryRepository;
    private final OutboxService outboxService;

    @Transactional
    public void handle(JsonNode rootNode, String deliveryId) {
        String action = rootNode.path("action").asText();
        JsonNode installationNode = rootNode.path("installation");
        if (installationNode.isMissingNode() || installationNode.isNull()) {
            return;
        }

        Long installationId = installationNode.path("id").asLong();
        JsonNode accountNode = installationNode.path("account");
        String accountLogin = accountNode.path("login").asText("unknown");
        String accountType = accountNode.path("type").asText("Organization");

        // Find existing or create new installation
        Optional<GitHubInstallation> existingOpt = installationRepository.findByInstallationId(installationId);
        GitHubInstallation installation;
        if (existingOpt.isPresent()) {
            installation = existingOpt.get();
            if ("deleted".equalsIgnoreCase(action)) {
                installation.setStatus(InstallationStatus.DELETED);
            } else if ("suspend".equalsIgnoreCase(action)) {
                installation.setStatus(InstallationStatus.SUSPENDED);
            } else if ("unsuspend".equalsIgnoreCase(action) || "created".equalsIgnoreCase(action)) {
                installation.setStatus(InstallationStatus.ACTIVE);
            }
            installation = installationRepository.save(installation);
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
                        .installationId(installationId)
                        .accountLogin(accountLogin)
                        .accountType(accountType)
                        .status(InstallationStatus.SUSPENDED)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
                installationRepository.save(installation);
                return; // Do not process attached repositories for quarantined installation
            }

            installation = GitHubInstallation.builder()
                    .organizationId(orgId)
                    .installationId(installationId)
                    .accountLogin(accountLogin)
                    .accountType(accountType)
                    .status(InstallationStatus.ACTIVE)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            installation = installationRepository.save(installation);
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
            repo = repositoryRepository.save(repo);
        } else {
            repo = Repository.builder()
                    .organizationId(installation.getOrganizationId())
                    .installationId(installation.getInstallationId())
                    .githubRepoId(githubRepoId)
                    .name(name)
                    .fullName(fullName)
                    .defaultBranch("main")
                    .isPrivate(isPrivate)
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
