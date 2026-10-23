package com.forgeai.testing.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "test_runs", indexes = {
        @Index(name = "idx_runs_commit", columnList = "organization_id, commit_sha"),
        @Index(name = "idx_runs_pr", columnList = "organization_id, repository_id, pull_request_number"),
        @Index(name = "idx_runs_workflow_run", columnList = "organization_id, repository_id, workflow_run_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestRunJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "scope", length = 32)
    @Builder.Default
    private String scope = "ORGANIZATION";

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(name = "commit_sha", nullable = false, length = 40)
    private String commitSha;

    @Column(name = "pull_request_number")
    private Integer pullRequestNumber;

    @Column(name = "workflow_run_id")
    private Long workflowRunId;

    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private String status = "RUNNING";

    @Column(name = "total_tests", nullable = false)
    @Builder.Default
    private Integer totalTests = 0;

    @Column(name = "passed_tests", nullable = false)
    @Builder.Default
    private Integer passedTests = 0;

    @Column(name = "failed_tests", nullable = false)
    @Builder.Default
    private Integer failedTests = 0;

    @Column(name = "skipped_tests", nullable = false)
    @Builder.Default
    private Integer skippedTests = 0;

    @Column(name = "duration_ms", nullable = false)
    @Builder.Default
    private Long durationMs = 0L;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // GitHub workflow lifecycle fields (populated by WorkflowRunIngestionService)

    @Column(name = "workflow_name", length = 255)
    private String workflowName;

    @Column(name = "run_number")
    private Integer runNumber;

    /**
     * GitHub workflow_run status: "queued", "in_progress", "completed".
     * Distinct from the ForgeAI test result status stored in the {@code status} column.
     */
    @Column(name = "github_run_status", length = 32)
    private String githubRunStatus;

    /**
     * GitHub workflow_run conclusion: "success", "failure", "cancelled", etc.
     * Null until the run reaches "completed" status.
     */
    @Column(name = "conclusion", length = 32)
    private String conclusion;

    @Column(name = "head_branch", length = 255)
    private String headBranch;

    @Column(name = "trigger_event", length = 64)
    private String triggerEvent;

    @Column(name = "html_url", columnDefinition = "TEXT")
    private String htmlUrl;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (startedAt == null) startedAt = Instant.now();
    }
}
