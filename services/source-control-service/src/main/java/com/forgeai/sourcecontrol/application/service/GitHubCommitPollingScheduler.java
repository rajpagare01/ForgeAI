package com.forgeai.sourcecontrol.application.service;

import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.domain.repository.RepositoryRepository;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubCommitMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "forgeai.github.commit-polling-enabled", havingValue = "true", matchIfMissing = true)
public class GitHubCommitPollingScheduler {

    private final RepositoryRepository repositoryRepository;
    private final RepositoryApplicationService repositoryApplicationService;
    private final GitHubAppClient gitHubAppClient;
    private final GitHubAppConfig gitHubAppConfig;

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
            if (repo.getInstallationId() == null || repo.getInstallationId() <= 0) {
                continue;
            }

            try {
                List<GitHubCommitMetadata> commits = gitHubAppClient.listCommits(
                        repo.getInstallationId(),
                        repo.getFullName(),
                        1
                );

                if (commits.isEmpty()) {
                    continue;
                }

                String latestSha = commits.get(0).sha();
                if (latestSha == null || latestSha.isBlank()) {
                    continue;
                }

                if (!latestSha.equals(repo.getLatestIndexedSha())) {
                    log.info("[Poller] Auto-detected new commit {} on repository {} (previous indexed SHA: {}); triggering autonomous sync & analysis",
                            latestSha, repo.getFullName(), repo.getLatestIndexedSha());

                    UUID orgId = repo.getOrganizationId();
                    UUID wsId = repo.getWorkspaceId();
                    repositoryApplicationService.reconcileRepository(orgId, wsId, repo.getId());
                }
            } catch (Exception e) {
                log.warn("[Poller] Error polling repository {}: {}", repo.getFullName(), e.getMessage());
            }
        }
    }
}

