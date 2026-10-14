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
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowRunIngestionService {

    /** Ordered lifecycle states. Higher index = newer state. */
    private static final java.util.List<String> STATUS_ORDER =
            java.util.List.of("queued", "in_progress", "completed");

    private final TestRunRepository testRunRepository;

    /**
     * Upsert a TestRun from a {@code workflow_run} delivery.
     *
     * @param request the ingestion request (org ID is trusted / installation-derived)
     * @return the created or reconciled {@link TestRun}
     */
    @Transactional
    public TestRun ingestWorkflowRun(WorkflowRunIngestionRequest request) {
        if (request.getRepositoryId() == null) {
            throw new IllegalArgumentException("repositoryId is required");
        }
        if (request.getOrganizationId() == null) {
            throw new IllegalArgumentException("organizationId is required (must be installation-derived)");
        }
        if (request.getWorkflowRunId() == null || request.getWorkflowRunId() == 0L) {
            throw new IllegalArgumentException("workflowRunId must be a non-zero GitHub workflow_run id");
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

            TestRun run = TestRun.builder()
                    .organizationId(request.getOrganizationId())
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
                    .totalTests(0)
                    .passedTests(0)
                    .failedTests(0)
                    .skippedTests(0)
                    .durationMs(0L)
                    .startedAt(request.getRunStartedAt() != null ? request.getRunStartedAt() : now)
                    .completedAt(request.getCompletedAt())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            TestRun saved = testRunRepository.save(run);
            log.info("Created TestRun for workflow_run: id={}, orgId={}, repoId={}, workflowRunId={}, status={}, conclusion={}",
                    saved.getId(), request.getOrganizationId(), request.getRepositoryId(),
                    request.getWorkflowRunId(), request.getStatus(), request.getConclusion());
            return saved;

        } else {
            // --- UPDATE (Reconcile) ---
            TestRun existing = existingOpt.get();
            String currentGithubStatus = existing.getGithubRunStatus();
            String incomingStatus = request.getStatus();

            // Newer-state-wins: don't regress from completed → in_progress/queued
            if (!isNewerOrEqual(currentGithubStatus, incomingStatus)) {
                log.info("Skipping stale workflow_run event: workflowRunId={}, currentStatus={}, incomingStatus={} (stale, would regress state)",
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
            String effectiveConclusion = request.getConclusion() != null ? request.getConclusion() : existing.getConclusion();
            existing.setStatus(mapGithubStatusToDomain(incomingStatus, effectiveConclusion));
            existing.setUpdatedAt(now);

            TestRun updated = testRunRepository.save(existing);
            log.info("Reconciled TestRun for workflow_run: id={}, orgId={}, workflowRunId={}, previousStatus={}, newStatus={}, conclusion={}",
                    updated.getId(), request.getOrganizationId(), request.getWorkflowRunId(),
                    currentGithubStatus, incomingStatus, updated.getConclusion());
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
     * Maps a GitHub workflow_run status string and conclusion to the ForgeAI {@link TestRunStatus}.
     */
    private TestRunStatus mapGithubStatusToDomain(String githubStatus, String conclusion) {
        if (githubStatus == null) return TestRunStatus.RUNNING;
        return switch (githubStatus.toLowerCase()) {
            case "queued" -> TestRunStatus.QUEUED;
            case "in_progress" -> TestRunStatus.RUNNING;
            case "completed" -> {
                if (conclusion == null) yield TestRunStatus.PASSED;
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
