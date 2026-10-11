package com.forgeai.sourcecontrol.application.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.forgeai.sourcecontrol.domain.event.BranchCreatedEvent;
import com.forgeai.sourcecontrol.domain.event.CommitCreatedEvent;
import com.forgeai.sourcecontrol.domain.model.Commit;
import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.model.InstallationStatus;
import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.domain.repository.CommitRepository;
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
public class PushEventHandler {

    private final RepositoryRepository repositoryRepository;
    private final GitHubInstallationRepository installationRepository;
    private final CommitRepository commitRepository;
    private final OutboxService outboxService;

    @Transactional
    public void handle(JsonNode rootNode, String deliveryId) {
        JsonNode repoNode = rootNode.path("repository");
        if (repoNode.isMissingNode() || repoNode.isNull()) {
            log.warn("Push event missing repository node for delivery {}", deliveryId);
            return;
        }

        Long githubRepoId = repoNode.path("id").asLong();
        String repoName = repoNode.path("name").asText("unknown");
        String fullName = repoNode.path("full_name").asText("unknown/unknown");
        String defaultBranch = repoNode.path("default_branch").asText("main");
        boolean isPrivate = repoNode.path("private").asBoolean(true);

        Long installationId = rootNode.path("installation").path("id").asLong(0L);

        // Resolve or create repository
        Repository repository = getOrCreateRepository(githubRepoId, installationId, repoName, fullName, defaultBranch, isPrivate);

        // Check for branch creation
        boolean isCreated = rootNode.path("created").asBoolean(false);
        String ref = rootNode.path("ref").asText();
        if (isCreated && ref.startsWith("refs/heads/")) {
            String branchName = ref.replace("refs/heads/", "");
            BranchCreatedEvent branchEvent = BranchCreatedEvent.builder()
                    .repositoryId(repository.getId())
                    .organizationId(repository.getOrganizationId())
                    .branchName(branchName)
                    .ref(ref)
                    .timestamp(Instant.now())
                    .build();
            outboxService.publish(branchEvent, deliveryId, "Branch", repository.getId() + ":" + branchName);
        }

        // Process commits
        JsonNode commitsNode = rootNode.path("commits");
        if (commitsNode.isArray()) {
            for (JsonNode commitNode : commitsNode) {
                processCommitNode(commitNode, repository, deliveryId);
            }
        }
    }

    private void processCommitNode(JsonNode commitNode, Repository repository, String deliveryId) {
        String sha = commitNode.path("id").asText();
        if (sha == null || sha.isBlank()) {
            sha = commitNode.path("sha").asText();
        }
        if (sha == null || sha.isBlank()) {
            return;
        }

        if (commitRepository.existsByRepositoryIdAndSha(repository.getId(), sha)) {
            log.debug("Commit {} already exists for repository {}", sha, repository.getId());
            return;
        }

        String message = commitNode.path("message").asText("");
        JsonNode authorNode = commitNode.path("author");
        String authorName = authorNode.path("name").asText("Unknown");
        String authorEmail = authorNode.path("email").asText("unknown@forgeai.com");

        Instant committedAt = Instant.now();
        String timestampStr = commitNode.path("timestamp").asText(null);
        if (timestampStr != null && !timestampStr.isBlank()) {
            try {
                committedAt = Instant.parse(timestampStr);
            } catch (Exception e) {
                log.debug("Could not parse commit timestamp '{}', defaulting to now", timestampStr);
            }
        }

        Commit commit = Commit.builder()
                .organizationId(repository.getOrganizationId())
                .repositoryId(repository.getId())
                .sha(sha)
                .message(message)
                .authorName(authorName)
                .authorEmail(authorEmail)
                .committedAt(committedAt)
                .createdAt(Instant.now())
                .build();

        Commit saved = commitRepository.save(commit);

        CommitCreatedEvent event = CommitCreatedEvent.builder()
                .commitId(saved.getId())
                .organizationId(repository.getOrganizationId())
                .repositoryId(repository.getId())
                .sha(saved.getSha())
                .message(saved.getMessage())
                .authorEmail(saved.getAuthorEmail())
                .authorName(saved.getAuthorName())
                .committedAt(saved.getCommittedAt())
                .timestamp(Instant.now())
                .build();

        outboxService.publish(event, deliveryId, "Commit", saved.getId().toString());
        log.info("Persisted commit and published CommitCreatedEvent: repo={}, sha={}", repository.getFullName(), sha);
    }

    private Repository getOrCreateRepository(Long githubRepoId, Long installationId, String repoName, String fullName, String defaultBranch, boolean isPrivate) {
        Optional<Repository> existing = repositoryRepository.findByGithubRepoId(githubRepoId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID orgId = UUID.randomUUID();
        if (installationId > 0) {
            Optional<GitHubInstallation> instOpt = installationRepository.findByInstallationId(installationId);
            if (instOpt.isPresent()) {
                orgId = instOpt.get().getOrganizationId();
            } else {
                GitHubInstallation inst = GitHubInstallation.builder()
                        .organizationId(orgId)
                        .installationId(installationId)
                        .accountLogin(fullName.contains("/") ? fullName.split("/")[0] : "forgeai")
                        .accountType("Organization")
                        .status(InstallationStatus.ACTIVE)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
                installationRepository.save(inst);
            }
        } else {
            installationId = 1001L;
            Optional<GitHubInstallation> instOpt = installationRepository.findByInstallationId(installationId);
            if (instOpt.isEmpty()) {
                GitHubInstallation inst = GitHubInstallation.builder()
                        .organizationId(orgId)
                        .installationId(installationId)
                        .accountLogin("forgeai")
                        .accountType("Organization")
                        .status(InstallationStatus.ACTIVE)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
                installationRepository.save(inst);
            }
        }

        Repository repo = Repository.builder()
                .organizationId(orgId)
                .installationId(installationId)
                .githubRepoId(githubRepoId)
                .name(repoName)
                .fullName(fullName)
                .defaultBranch(defaultBranch)
                .isPrivate(isPrivate)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        return repositoryRepository.save(repo);
    }
}
