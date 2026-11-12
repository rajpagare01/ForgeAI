package com.forgeai.testing.domain.repository;

import com.forgeai.testing.domain.model.TestCase;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestCaseRepository {
    TestCase save(TestCase testCase);
    Optional<TestCase> findByIdAndOrganizationId(UUID id, UUID organizationId);
    Optional<TestCase> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
    Optional<TestCase> findBySuiteIdAndNameAndClassname(UUID suiteId, String name, String classname);
    List<TestCase> findAllBySuiteId(UUID suiteId);
    List<TestCase> findAllByOrganizationIdAndRequirementKey(UUID organizationId, String requirementKey);
    List<TestCase> findAllByWorkspaceIdAndRequirementKey(UUID workspaceId, String requirementKey);
}
