package com.forgeai.sourcecontrol.application.service;

import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.domain.repository.RepositoryRepository;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubBranchMetadata;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubCommitMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "forgeai.github.commit-polling-enabled", havingValue = "true", matchIfMissing = true)
public class GitHubCommitPollingScheduler {

    private static final UUID QUARANTINE_ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final RepositoryRepository repositoryRepository;
    private final RepositoryApplicationService repositoryApplicationService;
    private final GitHubAppClient gitHubAppClient;
    private final GitHubAppConfig gitHubAppConfig;
    private final ConcurrentHashMap<Long, String> forwardedWorkflowRunStates = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.forgeai.sourcecontrol.infrastructure.messaging.WorkflowRunTestingServiceForwarder workflowRunTestingServiceForwarder;

    @Scheduled(
            fixedDelayString = "${forgeai.github.commit-poll-interval-ms:30000}",
            initialDelayString = "${forgeai.github.commit-poll-initial-delay-ms:15000}"
    )
    public void pollRecentCommits() {
        if (!gitHubAppConfig.isConfigured()) {
            return;
        }

        List<Repository> repositories;
        try {
            repositories = repositoryRepository.findAll();
        } catch (Exception e) {
            log.warn("[Poller] Failed to query repositories: {}", e.getMessage());
            return;
        }

        for (Repository repo : repositories) {
            if (repo.getInstallationId() == null || repo.getInstallationId() <= 0 || QUARANTINE_ORG_ID.equals(repo.getOrganizationId())) {
                continue;
            }

            try {
                List<GitHubBranchMetadata> branches = gitHubAppClient.listBranches(
                        repo.getInstallationId(),
                        repo.getFullName()
                );

                String latestSha = null;
                String targetBranch = null;

                if (branches != null && !branches.isEmpty()) {
                    // Only track the default branch (falling back to the first branch) to prevent multi-branch oscillation.
                    GitHubBranchMetadata trackedBranch = branches.stream()
                            .filter(b -> repo.getDefaultBranch() != null && repo.getDefaultBranch().equalsIgnoreCase(b.name()))
                            .findFirst()
                            .orElse(branches.get(0));

                    if (!trackedBranch.commitSha().equals(repo.getLatestIndexedSha())
                            && !trackedBranch.commitSha().equals(repo.getLatestObservedSha())) {
                        latestSha = trackedBranch.commitSha();
                        targetBranch = trackedBranch.name();
                    }
                } else {
                    List<GitHubCommitMetadata> commits = gitHubAppClient.listCommits(
                            repo.getInstallationId(),
                            repo.getFullName(),
                            1
                    );
                    if (!commits.isEmpty()) {
                        String commitSha = commits.get(0).sha();
                        if (!commitSha.equals(repo.getLatestIndexedSha())
                                && !commitSha.equals(repo.getLatestObservedSha())) {
                            latestSha = commitSha;
                            targetBranch = repo.getDefaultBranch();
                        }
                    }
                }

                if (latestSha != null && !latestSha.isBlank()) {
                    log.info("[Poller] Auto-detected new commit {} on repository {} (branch: {}, previous indexed SHA: {}); triggering autonomous sync & analysis",
                            latestSha, repo.getFullName(), targetBranch, repo.getLatestIndexedSha());

                    UUID orgId = repo.getOrganizationId();
                    UUID wsId = repo.getWorkspaceId();
                    repositoryApplicationService.reconcileRepository(orgId, wsId, repo.getId(), targetBranch);
                }
            } catch (Exception e) {
                log.warn("[Poller] Error polling repository {}: {}", repo.getFullName(), e.getMessage());
            }

            try {
                if (workflowRunTestingServiceForwarder != null) {
                    List<GitHubAppClient.GitHubWorkflowRun> workflowRuns = gitHubAppClient.listWorkflowRuns(
                            repo.getInstallationId(),
                            repo.getFullName(),
                            5
                    );
                    for (var r : workflowRuns) {
                        String stateKey = (r.status() != null ? r.status() : "") + ":" + (r.conclusion() != null ? r.conclusion() : "");
                        if (stateKey.equals(forwardedWorkflowRunStates.get(r.id()))) {
                            continue;
                        }

                        String scope = repo.getScope() != null ? repo.getScope() :
                                (repo.getOrganizationId() != null ? "ORGANIZATION" : "PERSONAL");
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
                                .timestamp(java.time.Instant.now())
                                .build();
                        workflowRunTestingServiceForwarder.onEventEnvelope(
                                com.forgeai.sourcecontrol.domain.event.EventEnvelope.from(
                                        event, "poll-" + r.id(), "WorkflowRun", String.valueOf(r.id()))
                        );
                        forwardedWorkflowRunStates.put(r.id(), stateKey);
                    }
                }
            } catch (Exception e) {
                log.warn("[Poller] Error polling workflow runs for repository {}: {}", repo.getFullName(), e.getMessage());
            }
        }
    }
}
