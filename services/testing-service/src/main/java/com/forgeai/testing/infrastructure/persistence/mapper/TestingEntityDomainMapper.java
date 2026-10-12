package com.forgeai.testing.infrastructure.persistence.mapper;

import com.forgeai.testing.domain.model.*;
import com.forgeai.testing.infrastructure.persistence.entity.*;
import org.springframework.stereotype.Component;

@Component
public class TestingEntityDomainMapper {

    public TestSuite toDomain(TestSuiteJpaEntity entity) {
        if (entity == null) return null;
        return TestSuite.builder()
                .id(entity.getId())
                .organizationId(entity.getOrganizationId())
                .repositoryId(entity.getRepositoryId())
                .name(entity.getName())
                .framework(entity.getFramework())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public TestSuiteJpaEntity toEntity(TestSuite domain) {
        if (domain == null) return null;
        return TestSuiteJpaEntity.builder()
                .id(domain.getId())
                .organizationId(domain.getOrganizationId())
                .repositoryId(domain.getRepositoryId())
                .name(domain.getName())
                .framework(domain.getFramework() != null ? domain.getFramework() : "JUNIT")
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }

    public TestCase toDomain(TestCaseJpaEntity entity) {
        if (entity == null) return null;
        return TestCase.builder()
                .id(entity.getId())
                .organizationId(entity.getOrganizationId())
                .suiteId(entity.getSuiteId())
                .name(entity.getName())
                .classname(entity.getClassname())
                .requirementKey(entity.getRequirementKey())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public TestCaseJpaEntity toEntity(TestCase domain) {
        if (domain == null) return null;
        return TestCaseJpaEntity.builder()
                .id(domain.getId())
                .organizationId(domain.getOrganizationId())
                .suiteId(domain.getSuiteId())
                .name(domain.getName())
                .classname(domain.getClassname())
                .requirementKey(domain.getRequirementKey())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }

    public TestRun toDomain(TestRunJpaEntity entity) {
        if (entity == null) return null;
        return TestRun.builder()
                .id(entity.getId())
                .organizationId(entity.getOrganizationId())
                .repositoryId(entity.getRepositoryId())
                .commitSha(entity.getCommitSha())
                .pullRequestNumber(entity.getPullRequestNumber())
                .workflowRunId(entity.getWorkflowRunId())
                .status(entity.getStatus() != null ? TestRunStatus.valueOf(entity.getStatus()) : TestRunStatus.RUNNING)
                .totalTests(entity.getTotalTests())
                .passedTests(entity.getPassedTests())
                .failedTests(entity.getFailedTests())
                .skippedTests(entity.getSkippedTests())
                .durationMs(entity.getDurationMs())
                .startedAt(entity.getStartedAt())
                .completedAt(entity.getCompletedAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public TestRunJpaEntity toEntity(TestRun domain) {
        if (domain == null) return null;
        return TestRunJpaEntity.builder()
                .id(domain.getId())
                .organizationId(domain.getOrganizationId())
                .repositoryId(domain.getRepositoryId())
                .commitSha(domain.getCommitSha())
                .pullRequestNumber(domain.getPullRequestNumber())
                .workflowRunId(domain.getWorkflowRunId())
                .status(domain.getStatus() != null ? domain.getStatus().name() : "RUNNING")
                .totalTests(domain.getTotalTests() != null ? domain.getTotalTests() : 0)
                .passedTests(domain.getPassedTests() != null ? domain.getPassedTests() : 0)
                .failedTests(domain.getFailedTests() != null ? domain.getFailedTests() : 0)
                .skippedTests(domain.getSkippedTests() != null ? domain.getSkippedTests() : 0)
                .durationMs(domain.getDurationMs() != null ? domain.getDurationMs() : 0L)
                .startedAt(domain.getStartedAt())
                .completedAt(domain.getCompletedAt())
                .createdAt(domain.getCreatedAt())
                .build();
    }

    public TestResult toDomain(TestResultJpaEntity entity) {
        if (entity == null) return null;
        return TestResult.builder()
                .id(entity.getId())
                .organizationId(entity.getOrganizationId())
                .testRunId(entity.getTestRunId())
                .testCaseId(entity.getTestCaseId())
                .status(entity.getStatus() != null ? TestResultStatus.valueOf(entity.getStatus()) : TestResultStatus.PASSED)
                .durationMs(entity.getDurationMs())
                .errorMessage(entity.getErrorMessage())
                .stackTrace(entity.getStackTrace())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public TestResultJpaEntity toEntity(TestResult domain) {
        if (domain == null) return null;
        return TestResultJpaEntity.builder()
                .id(domain.getId())
                .organizationId(domain.getOrganizationId())
                .testRunId(domain.getTestRunId())
                .testCaseId(domain.getTestCaseId())
                .status(domain.getStatus() != null ? domain.getStatus().name() : "PASSED")
                .durationMs(domain.getDurationMs() != null ? domain.getDurationMs() : 0L)
                .errorMessage(domain.getErrorMessage())
                .stackTrace(domain.getStackTrace())
                .createdAt(domain.getCreatedAt())
                .build();
    }
}
