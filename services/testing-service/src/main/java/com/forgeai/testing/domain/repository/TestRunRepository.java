package com.forgeai.testing.domain.repository;

import com.forgeai.testing.domain.model.TestRun;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestRunRepository {
    TestRun save(TestRun run);
    Optional<TestRun> findByIdAndOrganizationId(UUID id, UUID organizationId);
    Optional<TestRun> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
    Optional<TestRun> findByRepositoryIdAndWorkflowRunId(UUID repositoryId, Long workflowRunId);
    List<TestRun> findAllByOrganizationId(UUID organizationId);
    List<TestRun> findAllByWorkspaceId(UUID workspaceId);
    List<TestRun> findAllByOrganizationIdAndRepositoryId(UUID organizationId, UUID repositoryId);
    List<TestRun> findAllByWorkspaceIdAndRepositoryId(UUID workspaceId, UUID repositoryId);
    List<TestRun> findAllByOrganizationIdAndCommitSha(UUID organizationId, String commitSha);
    List<TestRun> findAllByWorkspaceIdAndCommitSha(UUID workspaceId, String commitSha);
    List<TestRun> findAllByOrganizationIdAndRepositoryIdAndPullRequestNumber(UUID organizationId, UUID repositoryId, Integer pullRequestNumber);
    List<TestRun> findAllByWorkspaceIdAndRepositoryIdAndPullRequestNumber(UUID workspaceId, UUID repositoryId, Integer pullRequestNumber);
}
