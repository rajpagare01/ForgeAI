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

import com.forgeai.sourcecontrol.domain.model.RepositorySyncStatus;
import com.forgeai.sourcecontrol.infrastructure.ai.AiServiceClient;

import java.time.Instant;
import java.util.*;

@Slf4j
@Component
public class PushEventHandler {

    private final RepositoryRepository repositoryRepository;
    private final GitHubInstallationRepository installationRepository;
    private final CommitRepository commitRepository;
    private final OutboxService outboxService;
    private final com.forgeai.sourcecontrol.infrastructure.ai.AiServiceClient aiServiceClient;
    private final com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient gitHubAppClient;

    public PushEventHandler(
            RepositoryRepository repositoryRepository,
            GitHubInstallationRepository installationRepository,
            CommitRepository commitRepository,
            OutboxService outboxService) {
        this(repositoryRepository, installationRepository, commitRepository, outboxService, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PushEventHandler(
            RepositoryRepository repositoryRepository,
            GitHubInstallationRepository installationRepository,
            CommitRepository commitRepository,
            OutboxService outboxService,
            com.forgeai.sourcecontrol.infrastructure.ai.AiServiceClient aiServiceClient,
            com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient gitHubAppClient) {
        this.repositoryRepository = repositoryRepository;
        this.installationRepository = installationRepository;
        this.commitRepository = commitRepository;
        this.outboxService = outboxService;
        this.aiServiceClient = aiServiceClient;
        this.gitHubAppClient = gitHubAppClient;
    }

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
                    .workspaceId(repository.getWorkspaceId())
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

        // Process Synchronization & Incremental Indexing for push events
        String afterSha = rootNode.path("after").asText(null);
        if (afterSha == null || afterSha.isBlank()) {
            JsonNode headCommit = rootNode.path("head_commit");
            if (!headCommit.isMissingNode() && !headCommit.isNull()) {
                afterSha = headCommit.path("id").asText(null);
                if (afterSha == null || afterSha.isBlank()) {
                    afterSha = headCommit.path("sha").asText(null);
                }
            }
        }

        if (afterSha != null && !afterSha.isBlank() && !afterSha.matches("^0+$")) {
            repository.setLatestObservedSha(afterSha);
            repository.setLastDeliveryId(deliveryId);

            boolean isDefaultBranchPush = ref.equals("refs/heads/" + defaultBranch) ||
                                          ref.equals(defaultBranch) ||
                                          ref.endsWith("/" + defaultBranch);

            if (isDefaultBranchPush) {
                if (aiServiceClient != null) {
                    repository.setSyncStatus(RepositorySyncStatus.SYNCING);
                    repository = repositoryRepository.save(repository);

                    // Collect authoritative changed files across commits
                    Map<String, String> fileStatusMap = new LinkedHashMap<>();
                    if (commitsNode.isArray()) {
                        for (JsonNode commitNode : commitsNode) {
                            JsonNode added = commitNode.path("added");
                            if (added.isArray()) {
                                for (JsonNode f : added) fileStatusMap.put(f.asText(), "added");
                            }
                            JsonNode modified = commitNode.path("modified");
                            if (modified.isArray()) {
                                for (JsonNode f : modified) {
                                    String path = f.asText();
                                    if (!"added".equals(fileStatusMap.get(path))) {
                                        fileStatusMap.put(path, "modified");
                                    }
                                }
                            }
                            JsonNode removed = commitNode.path("removed");
                            if (removed.isArray()) {
                                for (JsonNode f : removed) {
                                    String path = f.asText();
                                    if ("added".equals(fileStatusMap.get(path))) {
                                        fileStatusMap.remove(path);
                                    } else {
                                        fileStatusMap.put(path, "removed");
                                    }
                                }
                            }
                        }
                    }

                    List<AiServiceClient.ChangedFilePayload> changedFiles = new ArrayList<>();
                    for (Map.Entry<String, String> entry : fileStatusMap.entrySet()) {
                        String path = entry.getKey();
                        String status = entry.getValue();
                        String content = null;
                        if (!"removed".equals(status) && gitHubAppClient != null && repository.getInstallationId() != null && repository.getInstallationId() > 0) {
                            try {
                                content = gitHubAppClient.getFileContent(repository.getInstallationId(), repository.getFullName(), path, afterSha).orElse(null);
                            } catch (Exception e) {
                                log.debug("Could not fetch file content for {}@{}: {}", path, afterSha, e.getMessage());
                            }
                        }
                        changedFiles.add(new AiServiceClient.ChangedFilePayload(path, status, content));
                    }

                    JsonNode headCommitNode = rootNode.path("head_commit");
                    String authorName = null;
                    String authorEmail = null;
                    String commitMessage = null;
                    if (!headCommitNode.isMissingNode() && !headCommitNode.isNull()) {
                        JsonNode authorNode = headCommitNode.path("author");
                        authorName = authorNode.path("name").asText(null);
                        authorEmail = authorNode.path("email").asText(null);
                        commitMessage = headCommitNode.path("message").asText(null);
                    }

                    boolean syncSuccess;
                    if (authorName != null || authorEmail != null || commitMessage != null) {
                        syncSuccess = aiServiceClient.syncPush(
                                repository.getOrganizationId() != null ? repository.getOrganizationId() : repository.getWorkspaceId(),
                                repository.getId(),
                                afterSha,
                                ref,
                                changedFiles,
                                authorName,
                                authorEmail,
                                commitMessage
                        );
                    } else {
                        syncSuccess = aiServiceClient.syncPush(
                                repository.getOrganizationId() != null ? repository.getOrganizationId() : repository.getWorkspaceId(),
                                repository.getId(),
                                afterSha,
                                ref,
                                changedFiles
                        );
                    }

                    if (syncSuccess) {
                        repository.setLatestIndexedSha(afterSha);
                        repository.setSyncStatus(RepositorySyncStatus.SYNCED);
                        repository.setLastSyncedAt(Instant.now());
                        repository.setLastError(null);
                    } else {
                        repository.setSyncStatus(RepositorySyncStatus.FAILED);
                        repository.setLastError("Failed to incrementally index commit " + afterSha);
                    }
                } else {
                    // Fallback when aiServiceClient is not bound (e.g. lightweight unit tests)
                    repository.setLatestIndexedSha(afterSha);
                    repository.setSyncStatus(RepositorySyncStatus.SYNCED);
                    repository.setLastSyncedAt(Instant.now());
                }
            }

            repositoryRepository.save(repository);
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
                .workspaceId(repository.getWorkspaceId())
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
                .workspaceId(repository.getWorkspaceId())
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

        UUID orgId = null;
        UUID wsId = null;
        String scope = "ORGANIZATION";
        if (installationId != null && installationId > 0) {
            Optional<GitHubInstallation> instOpt = installationRepository.findByInstallationId(installationId);
            if (instOpt.isPresent()) {
                GitHubInstallation inst = instOpt.get();
                if (!UUID.fromString("00000000-0000-0000-0000-000000000000").equals(inst.getOrganizationId())) {
                    if ("PERSONAL".equalsIgnoreCase(inst.getScope()) || (inst.getOrganizationId() == null && inst.getWorkspaceId() != null)) {
                        wsId = inst.getWorkspaceId();
                        orgId = null;
                        scope = "PERSONAL";
                    } else if (inst.getOrganizationId() != null) {
                        orgId = inst.getOrganizationId();
                        wsId = inst.getWorkspaceId() != null ? inst.getWorkspaceId() : orgId;
                        scope = "ORGANIZATION";
                    }
                }
            }
        }

        if (orgId == null && wsId == null) {
            UUID QUARANTINE_ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
            log.warn("Push received for unmapped repository/installation {} (repo: {}). Quarantining under tenant {}.", installationId, fullName, QUARANTINE_ORG_ID);
            orgId = QUARANTINE_ORG_ID;
            wsId = QUARANTINE_ORG_ID;
            long effectiveInstId = (installationId != null && installationId > 0) ? installationId : 0L;
            if (effectiveInstId > 0 && installationRepository.findByInstallationId(effectiveInstId).isEmpty()) {
                GitHubInstallation inst = GitHubInstallation.builder()
                        .organizationId(QUARANTINE_ORG_ID)
                        .workspaceId(QUARANTINE_ORG_ID)
                        .scope("ORGANIZATION")
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
                .workspaceId(wsId)
                .scope(scope)
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
