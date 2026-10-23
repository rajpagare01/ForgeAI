package com.forgeai.testing.domain.repository;

import com.forgeai.testing.domain.model.TestResult;

import java.util.List;
import java.util.UUID;

public interface TestResultRepository {
    TestResult save(TestResult result);
    List<TestResult> saveAll(List<TestResult> results);
    List<TestResult> findAllByOrganizationIdAndTestRunId(UUID organizationId, UUID testRunId);
    List<TestResult> findAllByWorkspaceIdAndTestRunId(UUID workspaceId, UUID testRunId);
    List<TestResult> findAllByOrganizationIdAndTestCaseId(UUID organizationId, UUID testCaseId);
    List<TestResult> findAllByWorkspaceIdAndTestCaseId(UUID workspaceId, UUID testCaseId);
}
