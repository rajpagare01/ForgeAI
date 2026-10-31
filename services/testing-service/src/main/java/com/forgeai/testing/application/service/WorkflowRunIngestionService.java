package com.forgeai.testing.application.service;

import com.forgeai.testing.application.dto.WorkflowRunIngestionRequest;
import com.forgeai.testing.domain.model.TestRun;
import com.forgeai.testing.domain.model.TestRunStatus;
import com.forgeai.testing.domain.repository.TestRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Ingests and reconciles GitHub {@code workflow_run} events into TestRun domain records.
 *
 * <h3>Reconciliation Strategy — Newer-State Wins</h3>
 * <p>GitHub workflow_run events arrive in progression: {@code queued → in_progress → completed}.
 * This service performs an <em>upsert</em> on the unique constraint
 * {@code (repository_id, workflow_run_id)}:
 * <ul>
 *   <li>If no record exists → create a new {@link TestRun}.</li>
 *   <li>If a record exists → update <em>only if</em> the incoming status represents a
 *       newer lifecycle state (i.e., does not regress from {@code completed} back to
 *       {@code in_progress} or {@code queued}).</li>
 * </ul>
 *
 * <h3>Tenant Association</h3>
 * <p>The {@code organizationId} in the request is trusted: it was derived from the
 * installation ↔ repository mapping in Source Control and is never taken from raw
 * GitHub webhook JSON.
 */
import com.forgeai.testing.infrastructure.logging.StructuredLogger;
import com.forgeai.testing.infrastructure.metrics.TestingMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class WorkflowRunIngestionService {

    /** Ordered lifecycle states. Higher index = newer state. */
    private static final java.util.List<String> STATUS_ORDER = java.util.List.of("queued", "in_progress", "completed");

    private final TestRunRepository testRunRepository;
    private final TestingMetrics testingMetrics;
    private final StructuredLogger structuredLogger;

    public WorkflowRunIngestionService(TestRunRepository testRunRepository) {
        this(testRunRepository, null, null);
    }

    @Autowired
    public WorkflowRunIngestionService(TestRunRepository testRunRepository,
            @Autowired(required = false) TestingMetrics testingMetrics,
            @Autowired(required = false) StructuredLogger structuredLogger) {
        this.testRunRepository = testRunRepository;
        this.testingMetrics = testingMetrics;
        this.structuredLogger = structuredLogger;
    }

    /**
     * Upsert a TestRun from a {@code workflow_run} delivery.
     *
     * @param request the ingestion request (org ID is trusted /
     *                installation-derived)
     * @return the created or reconciled {@link TestRun}
     */
    @Transactional
    public TestRun ingestWorkflowRun(WorkflowRunIngestionRequest request) {
        if (request.getRepositoryId() == null) {
            throw new IllegalArgumentException("repositoryId is required");
        }
        if (request.getOrganizationId() == null && request.getWorkspaceId() == null) {
            throw new IllegalArgumentException(
                    "workspaceId or organizationId is required (must be installation-derived)");
        }
        if (request.getWorkflowRunId() == null || request.getWorkflowRunId() == 0L) {
            throw new IllegalArgumentException("workflowRunId must be a non-zero GitHub workflow_run id");
        }

        UUID effectiveWsId = request.getWorkspaceId() != null ? request.getWorkspaceId() : request.getOrganizationId();
        String effectiveScope = request.getScope() != null ? request.getScope()
                : (request.getOrganizationId() != null ? "ORGANIZATION" : "PERSONAL");

        if (testingMetrics != null) {
            testingMetrics.recordWorkflowRunReceived();
        }

        Optional<TestRun> existingOpt = testRunRepository.findByRepositoryIdAndWorkflowRunId(
                request.getRepositoryId(), request.getWorkflowRunId());

        Instant now = Instant.now();

        if (existingOpt.isEmpty()) {
            // --- CREATE ---
            // Map GitHub status to TestRunStatus
            TestRunStatus domainStatus = mapGithubStatusToDomain(request.getStatus(), request.getConclusion());

            // Use a placeholder SHA if headSha is missing (defensive)
            String commitSha = (request.getHeadSha() != null && !request.getHeadSha().isBlank())
                    ? request.getHeadSha()
                    : "0000000000000000000000000000000000000000";

            long computedDurationMs = 0L;
            if (request.getRunStartedAt() != null && request.getCompletedAt() != null) {
                computedDurationMs = Math.max(0L,
                        java.time.Duration.between(request.getRunStartedAt(), request.getCompletedAt()).toMillis());
            }

            int totalTests = 0;
            int passedTests = 0;
            int failedTests = 0;
            if (domainStatus == TestRunStatus.PASSED) {
                passedTests = 1;
                totalTests = 1;
            } else if (domainStatus == TestRunStatus.FAILED) {
                failedTests = 1;
                totalTests = 1;
            }

            TestRun run = TestRun.builder()
                    .workspaceId(effectiveWsId)
                    .organizationId(request.getOrganizationId())
                    .scope(effectiveScope)
                    .repositoryId(request.getRepositoryId())
                    .commitSha(commitSha)
                    .workflowRunId(request.getWorkflowRunId())
                    .workflowName(request.getWorkflowName())
                    .runNumber(request.getRunNumber())
                    .githubRunStatus(request.getStatus())
                    .conclusion(request.getConclusion())
                    .headBranch(request.getHeadBranch())
                    .triggerEvent(request.getTriggerEvent())
                    .htmlUrl(request.getHtmlUrl())
                    .status(domainStatus)
                    .totalTests(totalTests)
                    .passedTests(passedTests)
                    .failedTests(failedTests)
                    .skippedTests(0)
                    .durationMs(computedDurationMs)
                    .startedAt(request.getRunStartedAt() != null ? request.getRunStartedAt() : now)
                    .completedAt(request.getCompletedAt())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            TestRun saved = testRunRepository.save(run);
            log.info(
                    "Created TestRun for workflow_run: id={}, orgId={}, repoId={}, workflowRunId={}, status={}, conclusion={}",
                    saved.getId(), request.getOrganizationId(), request.getRepositoryId(),
                    request.getWorkflowRunId(), request.getStatus(), request.getConclusion());

            if (domainStatus == TestRunStatus.PASSED) {
                if (testingMetrics != null)
                    testingMetrics.recordWorkflowRunCompleted();
                if (structuredLogger != null) {
                    structuredLogger.info("testing.workflow.completed", Map.of(
                            "workflowRunId", request.getWorkflowRunId(),
                            "repositoryId", String.valueOf(request.getRepositoryId()),
                            "conclusion", String.valueOf(request.getConclusion())));
                }
            } else if (domainStatus == TestRunStatus.FAILED) {
                if (testingMetrics != null)
                    testingMetrics.recordWorkflowRunFailed();
                if (structuredLogger != null) {
                    structuredLogger.logEvent("WARN", "testing.workflow.failed", "workflow_run", "FAILURE", null, null,
                            "Workflow run failed: " + request.getWorkflowRunId(), Map.of(
                                    "workflowRunId", request.getWorkflowRunId(),
                                    "repositoryId", String.valueOf(request.getRepositoryId()),
                                    "conclusion", String.valueOf(request.getConclusion())));
                }
            }

            return saved;

        } else {
            // --- UPDATE (Reconcile) ---
            TestRun existing = existingOpt.get();
            String currentGithubStatus = existing.getGithubRunStatus();
            String incomingStatus = request.getStatus();

            // Newer-state-wins: don't regress from completed → in_progress/queued
            if (!isNewerOrEqual(currentGithubStatus, incomingStatus)) {
                if (testingMetrics != null) {
                    testingMetrics.recordWorkflowRunRejectedStale();
                }
                if (structuredLogger != null) {
                    structuredLogger.warn("testing.workflow.rejected_stale", Map.of(
                            "workflowRunId", request.getWorkflowRunId(),
                            "currentStatus", String.valueOf(currentGithubStatus),
                            "incomingStatus", String.valueOf(incomingStatus),
                            "repositoryId", String.valueOf(request.getRepositoryId())));
                }
                log.info(
                        "Skipping stale workflow_run event: workflowRunId={}, currentStatus={}, incomingStatus={} (stale, would regress state)",
                        request.getWorkflowRunId(), currentGithubStatus, incomingStatus);
                return existing;
            }

            // Update lifecycle fields
            existing.setGithubRunStatus(incomingStatus);
            if (request.getConclusion() != null) {
                existing.setConclusion(request.getConclusion());
            }
            if (request.getCompletedAt() != null) {
                existing.setCompletedAt(request.getCompletedAt());
            }
            if (request.getHeadBranch() != null) {
                existing.setHeadBranch(request.getHeadBranch());
            }
            if (request.getHtmlUrl() != null) {
                existing.setHtmlUrl(request.getHtmlUrl());
            }
            // Sync domain status
            String effectiveConclusion = request.getConclusion() != null ? request.getConclusion()
                    : existing.getConclusion();
            TestRunStatus domainStatus = mapGithubStatusToDomain(incomingStatus, effectiveConclusion);
            existing.setStatus(domainStatus);

            Instant start = request.getRunStartedAt() != null ? request.getRunStartedAt() : existing.getStartedAt();
            Instant end = request.getCompletedAt() != null ? request.getCompletedAt() : existing.getCompletedAt();
            if (start != null && end != null) {
                long computedDurationMs = Math.max(0L, java.time.Duration.between(start, end).toMillis());
                if (computedDurationMs > 0L) {
                    existing.setDurationMs(computedDurationMs);
                }
            }
            if (existing.getTotalTests() == null || existing.getTotalTests() == 0) {
                if (domainStatus == TestRunStatus.PASSED) {
                    existing.setPassedTests(1);
                    existing.setTotalTests(1);
                    existing.setFailedTests(0);
                } else if (domainStatus == TestRunStatus.FAILED) {
                    existing.setFailedTests(1);
                    existing.setTotalTests(1);
                    existing.setPassedTests(0);
                }
            }
            existing.setUpdatedAt(now);

            TestRun updated = testRunRepository.save(existing);
            log.info(
                    "Reconciled TestRun for workflow_run: id={}, orgId={}, workflowRunId={}, previousStatus={}, newStatus={}, conclusion={}",
                    updated.getId(), request.getOrganizationId(), request.getWorkflowRunId(),
                    currentGithubStatus, incomingStatus, updated.getConclusion());

            if (domainStatus == TestRunStatus.PASSED) {
                if (testingMetrics != null)
                    testingMetrics.recordWorkflowRunCompleted();
                if (structuredLogger != null) {
                    structuredLogger.info("testing.workflow.completed", Map.of(
                            "workflowRunId", request.getWorkflowRunId(),
                            "repositoryId", String.valueOf(request.getRepositoryId()),
                            "conclusion", String.valueOf(request.getConclusion())));
                }
            } else if (domainStatus == TestRunStatus.FAILED) {
                if (testingMetrics != null)
                    testingMetrics.recordWorkflowRunFailed();
                if (structuredLogger != null) {
                    structuredLogger.logEvent("WARN", "testing.workflow.failed", "workflow_run", "FAILURE", null, null,
                            "Workflow run failed: " + request.getWorkflowRunId(), Map.of(
                                    "workflowRunId", request.getWorkflowRunId(),
                                    "repositoryId", String.valueOf(request.getRepositoryId()),
                                    "conclusion", String.valueOf(request.getConclusion())));
                }
            }

            return updated;
        }
    }

    /**
     * Returns true if {@code incoming} is newer than or equal to {@code current}
     * in the GitHub workflow lifecycle order: queued < in_progress < completed.
     */
    private boolean isNewerOrEqual(String current, String incoming) {
        int currentIndex = STATUS_ORDER.indexOf(current != null ? current.toLowerCase() : "");
        int incomingIndex = STATUS_ORDER.indexOf(incoming != null ? incoming.toLowerCase() : "");
        return incomingIndex >= currentIndex;
    }

    /**
     * Maps a GitHub workflow_run status string and conclusion to the ForgeAI
     * {@link TestRunStatus}.
     */
    private TestRunStatus mapGithubStatusToDomain(String githubStatus, String conclusion) {
        if (githubStatus == null)
            return TestRunStatus.RUNNING;
        return switch (githubStatus.toLowerCase()) {
            case "queued" -> TestRunStatus.QUEUED;
            case "in_progress" -> TestRunStatus.RUNNING;
            case "completed" -> {
                if (conclusion == null)
                    yield TestRunStatus.PASSED;
                yield switch (conclusion.toLowerCase()) {
                    case "failure", "timed_out" -> TestRunStatus.FAILED;
                    case "cancelled" -> TestRunStatus.CANCELLED;
                    case "success", "skipped", "neutral" -> TestRunStatus.PASSED;
                    default -> TestRunStatus.PASSED;
                };
            }
            default -> TestRunStatus.RUNNING;
        };
    }
}
