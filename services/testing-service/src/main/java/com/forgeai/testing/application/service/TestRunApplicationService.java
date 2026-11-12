package com.forgeai.testing.application.service;

import com.forgeai.testing.application.command.CreateTestRunCommand;
import com.forgeai.testing.domain.event.TestRunCompletedEvent;
import com.forgeai.testing.domain.event.TestRunFailedEvent;
import com.forgeai.testing.domain.exception.ResourceNotFoundException;
import com.forgeai.testing.domain.model.TestResult;
import com.forgeai.testing.domain.model.TestResultStatus;
import com.forgeai.testing.domain.model.TestRun;
import com.forgeai.testing.domain.model.TestRunStatus;
import com.forgeai.testing.domain.repository.TestCaseRepository;
import com.forgeai.testing.domain.repository.TestResultRepository;
import com.forgeai.testing.domain.repository.TestRunRepository;
import com.forgeai.testing.infrastructure.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestRunApplicationService {

    private final TestRunRepository testRunRepository;
    private final TestResultRepository testResultRepository;
    private final TestCaseRepository testCaseRepository;
    private final OutboxService outboxService;

    private UUID resolveWorkspace(UUID workspaceId, UUID organizationId) {
        if (organizationId != null) return organizationId;
        if (workspaceId != null) return workspaceId;
        throw new IllegalArgumentException("Workspace ID or Organization ID must be provided via tenant context");
    }

    private String resolveScope(String scope, UUID organizationId) {
        if (organizationId != null) return "ORGANIZATION";
        if (scope != null) return scope;
        return "PERSONAL";
    }

    @Transactional
    public TestRun createTestRun(UUID organizationId, CreateTestRunCommand command) {
        return createTestRun(organizationId, organizationId, "ORGANIZATION", command);
    }

    @Transactional
    public TestRun createTestRun(UUID workspaceId, UUID organizationId, String scope, CreateTestRunCommand command) {
        UUID effectiveWsId = resolveWorkspace(workspaceId, organizationId);
        String effectiveScope = resolveScope(scope, organizationId);

        Instant now = Instant.now();
        TestRun run = TestRun.builder()
                .workspaceId(effectiveWsId)
                .organizationId(organizationId)
                .scope(effectiveScope)
                .repositoryId(command.repositoryId())
                .commitSha(command.commitSha() != null ? command.commitSha().trim() : null)
                .pullRequestNumber(command.pullRequestNumber())
                .workflowRunId(command.workflowRunId())
                .status(TestRunStatus.RUNNING)
                .totalTests(0)
                .passedTests(0)
                .failedTests(0)
                .skippedTests(0)
                .durationMs(0L)
                .startedAt(now)
                .createdAt(now)
                .build();

        TestRun saved = testRunRepository.save(run);
        log.info("Created test run: id={}, wsId={}, orgId={}, repo={}, commit={}",
                saved.getId(), effectiveWsId, organizationId, command.repositoryId(), command.commitSha());
        return saved;
    }

    @Transactional(readOnly = true)
    public TestRun getTestRun(UUID organizationId, UUID runId) {
        return getTestRun(organizationId, organizationId, runId);
    }

    @Transactional(readOnly = true)
    public TestRun getTestRun(UUID workspaceId, UUID organizationId, UUID runId) {
        UUID effectiveWsId = resolveWorkspace(workspaceId, organizationId);
        return testRunRepository.findByIdAndWorkspaceId(runId, effectiveWsId)
                .orElseThrow(() -> new ResourceNotFoundException("Test run not found: " + runId));
    }

    @Transactional
    public TestRun completeTestRun(UUID organizationId, UUID runId) {
        return completeTestRun(organizationId, organizationId, runId);
    }

    @Transactional
    public TestRun completeTestRun(UUID workspaceId, UUID organizationId, UUID runId) {
        UUID effectiveWsId = resolveWorkspace(workspaceId, organizationId);

        TestRun run = testRunRepository.findByIdAndWorkspaceId(runId, effectiveWsId)
                .orElseThrow(() -> new ResourceNotFoundException("Test run not found: " + runId));

        List<TestResult> results = testResultRepository.findAllByWorkspaceIdAndTestRunId(effectiveWsId, runId);

        int passed = 0;
        int failed = 0;
        int skipped = 0;
        long duration = 0L;

        for (TestResult r : results) {
            if (r.getStatus() == TestResultStatus.PASSED) {
                passed++;
            } else if (r.getStatus() == TestResultStatus.FAILED) {
                failed++;
            } else if (r.getStatus() == TestResultStatus.SKIPPED) {
                skipped++;
            }
            if (r.getDurationMs() != null) {
                duration += r.getDurationMs();
            }
        }

        run.finalizeRun(passed, failed, skipped, duration);
        TestRun updated = testRunRepository.save(run);

        if (failed > 0) {
            TestRunFailedEvent failedEvent = TestRunFailedEvent.builder()
                    .testRunId(updated.getId())
                    .organizationId(updated.getOrganizationId())
                    .workspaceId(updated.getWorkspaceId())
                    .scope(updated.getScope())
                    .repositoryId(updated.getRepositoryId())
                    .commitSha(updated.getCommitSha())
                    .totalTests(updated.getTotalTests())
                    .failedTests(failed)
                    .timestamp(Instant.now())
                    .build();
            outboxService.publish(failedEvent, "run-complete-" + updated.getId());
        } else {
            TestRunCompletedEvent completedEvent = TestRunCompletedEvent.builder()
                    .testRunId(updated.getId())
                    .organizationId(updated.getOrganizationId())
                    .workspaceId(updated.getWorkspaceId())
                    .scope(updated.getScope())
                    .repositoryId(updated.getRepositoryId())
                    .commitSha(updated.getCommitSha())
                    .totalTests(updated.getTotalTests())
                    .passedTests(passed)
                    .failedTests(failed)
                    .skippedTests(skipped)
                    .durationMs(duration)
                    .timestamp(Instant.now())
                    .build();
            outboxService.publish(completedEvent, "run-complete-" + updated.getId());
        }

        log.info("Finalized test run: id={}, status={}, total={}, passed={}, failed={}, skipped={}",
                updated.getId(), updated.getStatus(), updated.getTotalTests(), passed, failed, skipped);
        return updated;
    }

    @Transactional(readOnly = true)
    public List<TestRun> listTestRuns(UUID organizationId, UUID repositoryId, String commitSha) {
        return listTestRuns(organizationId, organizationId, repositoryId, commitSha);
    }

    @Transactional(readOnly = true)
    public List<TestRun> listTestRuns(UUID workspaceId, UUID organizationId, UUID repositoryId, String commitSha) {
        UUID effectiveWsId = resolveWorkspace(workspaceId, organizationId);
        if (commitSha != null && !commitSha.isBlank()) {
            return testRunRepository.findAllByWorkspaceIdAndCommitSha(effectiveWsId, commitSha.trim());
        }
        if (repositoryId != null) {
            return testRunRepository.findAllByWorkspaceIdAndRepositoryId(effectiveWsId, repositoryId);
        }
        return testRunRepository.findAllByWorkspaceId(effectiveWsId);
    }

    @Transactional(readOnly = true)
    public List<TestResult> getTestResults(UUID organizationId, UUID runId) {
        return getTestResults(organizationId, organizationId, runId);
    }

    @Transactional(readOnly = true)
    public List<TestResult> getTestResults(UUID workspaceId, UUID organizationId, UUID runId) {
        UUID effectiveWsId = resolveWorkspace(workspaceId, organizationId);
        List<TestResult> results = testResultRepository.findAllByWorkspaceIdAndTestRunId(effectiveWsId, runId);
        for (TestResult r : results) {
            if (r.getTestCaseId() != null) {
                testCaseRepository.findByIdAndWorkspaceId(r.getTestCaseId(), effectiveWsId).ifPresent(tc -> {
                    r.setName(tc.getName());
                    r.setTestCaseName(tc.getName());
                    r.setClassname(tc.getClassname());
                });
            }
        }
        return results;
    }
}
