package com.forgeai.testing.application.service;

import com.forgeai.testing.application.command.CreateTestRunCommand;
import com.forgeai.testing.domain.event.TestRunCompletedEvent;
import com.forgeai.testing.domain.event.TestRunFailedEvent;
import com.forgeai.testing.domain.exception.ResourceNotFoundException;
import com.forgeai.testing.domain.model.TestResult;
import com.forgeai.testing.domain.model.TestResultStatus;
import com.forgeai.testing.domain.model.TestRun;
import com.forgeai.testing.domain.model.TestRunStatus;
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
    private final OutboxService outboxService;

    @Transactional
    public TestRun createTestRun(UUID organizationId, CreateTestRunCommand command) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must be provided via tenant context");
        }

        Instant now = Instant.now();
        TestRun run = TestRun.builder()
                .organizationId(organizationId)
                .repositoryId(command.repositoryId())
                .commitSha(command.commitSha())
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
        log.info("Created test run: id={}, repo={}, commit={}", saved.getId(), command.repositoryId(), command.commitSha());
        return saved;
    }

    @Transactional(readOnly = true)
    public TestRun getTestRun(UUID organizationId, UUID runId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must be provided via tenant context");
        }
        return testRunRepository.findByIdAndOrganizationId(runId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Test run not found: " + runId));
    }

    @Transactional
    public TestRun completeTestRun(UUID organizationId, UUID runId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must be provided via tenant context");
        }

        TestRun run = testRunRepository.findByIdAndOrganizationId(runId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Test run not found: " + runId));

        List<TestResult> results = testResultRepository.findAllByOrganizationIdAndTestRunId(organizationId, runId);

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
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must be provided via tenant context");
        }
        if (commitSha != null && !commitSha.isBlank()) {
            return testRunRepository.findAllByOrganizationIdAndCommitSha(organizationId, commitSha);
        }
        if (repositoryId != null) {
            return testRunRepository.findAllByOrganizationIdAndRepositoryId(organizationId, repositoryId);
        }
        return testRunRepository.findAllByOrganizationId(organizationId);
    }

    @Transactional(readOnly = true)
    public List<TestResult> getTestResults(UUID organizationId, UUID runId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must be provided via tenant context");
        }
        return testResultRepository.findAllByOrganizationIdAndTestRunId(organizationId, runId);
    }
}
