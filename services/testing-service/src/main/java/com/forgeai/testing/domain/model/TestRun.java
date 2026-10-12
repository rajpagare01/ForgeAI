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
