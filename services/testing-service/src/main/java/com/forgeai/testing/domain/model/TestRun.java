package com.forgeai.testing.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestRun {
    private UUID id;
    private UUID organizationId;
    private UUID repositoryId;
    private String commitSha;
    private Integer pullRequestNumber;
    private Long workflowRunId;
    private TestRunStatus status;
    private Integer totalTests;
    private Integer passedTests;
    private Integer failedTests;
    private Integer skippedTests;
    private Long durationMs;
    private Instant startedAt;
    private Instant completedAt;
    private Instant createdAt;

    // GitHub workflow lifecycle fields
    private String workflowName;
    private Integer runNumber;
    /** GitHub run status: "queued", "in_progress", "completed" */
    private String githubRunStatus;
    /** GitHub run conclusion: "success", "failure", etc. Null until completed. */
    private String conclusion;
    private String headBranch;
    private String triggerEvent;
    private String htmlUrl;
    private Instant updatedAt;

    public void finalizeRun(int passed, int failed, int skipped, long duration) {
        this.passedTests = passed;
        this.failedTests = failed;
        this.skippedTests = skipped;
        this.totalTests = passed + failed + skipped;
        this.durationMs = duration;
        this.completedAt = Instant.now();
        this.status = failed > 0 ? TestRunStatus.FAILED : TestRunStatus.PASSED;
    }
}
