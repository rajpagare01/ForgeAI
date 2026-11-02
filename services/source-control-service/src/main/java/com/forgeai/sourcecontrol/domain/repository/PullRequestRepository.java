package com.forgeai.sourcecontrol.domain.repository;

import com.forgeai.sourcecontrol.domain.model.PullRequest;

import java.util.Optional;
import java.util.UUID;

public interface PullRequestRepository {
    PullRequest save(PullRequest pullRequest);
    Optional<PullRequest> findById(UUID id);
    Optional<PullRequest> findByRepositoryIdAndNumber(UUID repositoryId, Integer number);
    Optional<PullRequest> findByGithubPrId(Long githubPrId);
    java.util.List<PullRequest> findAllByOrganizationId(UUID organizationId);
    java.util.List<PullRequest> findAllByRepositoryId(UUID repositoryId);
    void deleteAllByRepositoryId(UUID repositoryId);
}
