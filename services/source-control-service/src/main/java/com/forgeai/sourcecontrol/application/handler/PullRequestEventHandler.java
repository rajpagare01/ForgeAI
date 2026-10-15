package com.forgeai.sourcecontrol.application.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.forgeai.sourcecontrol.domain.event.PullRequestMergedEvent;
import com.forgeai.sourcecontrol.domain.event.PullRequestOpenedEvent;
import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.model.InstallationStatus;
import com.forgeai.sourcecontrol.domain.model.PullRequest;
import com.forgeai.sourcecontrol.domain.model.PullRequestStatus;
import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import com.forgeai.sourcecontrol.domain.repository.PullRequestRepository;
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
public class PullRequestEventHandler {

    private final RepositoryRepository repositoryRepository;
    private final GitHubInstallationRepository installationRepository;
    private final PullRequestRepository pullRequestRepository;
    private final OutboxService outboxService;

    public PullRequestEventHandler(
            RepositoryRepository repositoryRepository,
            GitHubInstallationRepository installationRepository,
            PullRequestRepository pullRequestRepository,
            OutboxService outboxService) {
        this.repositoryRepository = repositoryRepository;
        this.installationRepository = installationRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.outboxService = outboxService;
    }

    @Transactional
    public void handle(JsonNode rootNode, String deliveryId) {
        String action = rootNode.path("action").asText();
        JsonNode prNode = rootNode.path("pull_request");
        JsonNode repoNode = rootNode.path("repository");

        if (prNode.isMissingNode() || prNode.isNull() || repoNode.isMissingNode() || repoNode.isNull()) {
            log.warn("PullRequest event missing pull_request or repository node for delivery {}", deliveryId);
            return;
        }

        Long githubRepoId = repoNode.path("id").asLong();
        String repoName = repoNode.path("name").asText("unknown");
        String fullName = repoNode.path("full_name").asText("unknown/unknown");
        String defaultBranch = repoNode.path("default_branch").asText("main");
        boolean isPrivate = repoNode.path("private").asBoolean(true);
        Long installationId = rootNode.path("installation").path("id").asLong(0L);

        Repository repository = getOrCreateRepository(githubRepoId, installationId, repoName, fullName, defaultBranch, isPrivate);

        Long githubPrId = prNode.path("id").asLong();
        int prNumber = prNode.path("number").asInt();
        String title = prNode.path("title").asText("");
        String body = prNode.path("body").asText(null);
        String sourceBranch = prNode.path("head").path("ref").asText("unknown");
        String targetBranch = prNode.path("base").path("ref").asText("main");
        String authorGithubId = prNode.path("user").path("login").asText("unknown");
        boolean isMerged = prNode.path("merged").asBoolean(false);

        PullRequestStatus status;
        if (isMerged) {
            status = PullRequestStatus.MERGED;
        } else if ("closed".equalsIgnoreCase(action)) {
            status = PullRequestStatus.CLOSED;
        } else {
            status = PullRequestStatus.OPEN;
        }

        Optional<PullRequest> existingOpt = pullRequestRepository.findByRepositoryIdAndNumber(repository.getId(), prNumber);
        PullRequest pullRequest;
        boolean isNew = existingOpt.isEmpty();

        if (isNew) {
            pullRequest = PullRequest.builder()
                    .organizationId(repository.getOrganizationId())
                    .repositoryId(repository.getId())
                    .githubPrId(githubPrId)
                    .number(prNumber)
                    .title(title)
                    .body(body)
                    .sourceBranch(sourceBranch)
                    .targetBranch(targetBranch)
                    .status(status)
                    .authorGithubId(authorGithubId)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
        } else {
            pullRequest = existingOpt.get();
            pullRequest.setTitle(title);
            pullRequest.setBody(body);
            pullRequest.setSourceBranch(sourceBranch);
            pullRequest.setTargetBranch(targetBranch);
            pullRequest.setStatus(status);
            pullRequest.setUpdatedAt(Instant.now());
        }

        PullRequest saved = pullRequestRepository.save(pullRequest);

        if ("opened".equalsIgnoreCase(action) || (isNew && status == PullRequestStatus.OPEN)) {
            PullRequestOpenedEvent event = PullRequestOpenedEvent.builder()
                    .pullRequestId(saved.getId())
                    .organizationId(saved.getOrganizationId())
                    .repositoryId(saved.getRepositoryId())
                    .githubPrId(saved.getGithubPrId())
                    .number(saved.getNumber())
                    .title(saved.getTitle())
                    .body(saved.getBody())
                    .sourceBranch(saved.getSourceBranch())
                    .targetBranch(saved.getTargetBranch())
                    .authorGithubId(saved.getAuthorGithubId())
                    .timestamp(Instant.now())
                    .build();
            outboxService.publish(event, deliveryId, "PullRequest", saved.getId().toString());
            log.info("Persisted PR and published PullRequestOpenedEvent: repo={}, number={}", repository.getFullName(), prNumber);
        } else if ("closed".equalsIgnoreCase(action) && isMerged) {
            String mergedBy = prNode.path("merged_by").path("login").asText("unknown");
            PullRequestMergedEvent event = PullRequestMergedEvent.builder()
                    .pullRequestId(saved.getId())
                    .organizationId(saved.getOrganizationId())
                    .repositoryId(saved.getRepositoryId())
                    .githubPrId(saved.getGithubPrId())
                    .number(saved.getNumber())
                    .title(saved.getTitle())
                    .mergedBy(mergedBy)
                    .mergedAt(Instant.now())
                    .timestamp(Instant.now())
                    .build();
            outboxService.publish(event, deliveryId, "PullRequest", saved.getId().toString());
            log.info("Updated PR and published PullRequestMergedEvent: repo={}, number={}", repository.getFullName(), prNumber);
        }
    }

    private Repository getOrCreateRepository(Long githubRepoId, Long installationId, String repoName, String fullName, String defaultBranch, boolean isPrivate) {
        Optional<Repository> existing = repositoryRepository.findByGithubRepoId(githubRepoId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID orgId = null;
        if (installationId != null && installationId > 0) {
            Optional<GitHubInstallation> instOpt = installationRepository.findByInstallationId(installationId);
            if (instOpt.isPresent() && !UUID.fromString("00000000-0000-0000-0000-000000000000").equals(instOpt.get().getOrganizationId())) {
                orgId = instOpt.get().getOrganizationId();
            }
        }

        if (orgId == null) {
            UUID QUARANTINE_ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
            log.warn("PullRequest received for unmapped repository/installation {} (repo: {}). Quarantining under tenant {}.", installationId, fullName, QUARANTINE_ORG_ID);
            orgId = QUARANTINE_ORG_ID;
            long effectiveInstId = (installationId != null && installationId > 0) ? installationId : 0L;
            if (effectiveInstId > 0 && installationRepository.findByInstallationId(effectiveInstId).isEmpty()) {
                GitHubInstallation inst = GitHubInstallation.builder()
                        .organizationId(QUARANTINE_ORG_ID)
                        .installationId(effectiveInstId)
                        .accountLogin(fullName.contains("/") ? fullName.split("/")[0] : "quarantine")
                        .accountType("Organization")
                        .status(InstallationStatus.SUSPENDED)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
                installationRepository.save(inst);
            }
            installationId = effectiveInstId;
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
