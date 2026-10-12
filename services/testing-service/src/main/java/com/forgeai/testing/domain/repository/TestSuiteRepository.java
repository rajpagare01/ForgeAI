package com.forgeai.testing.domain.repository;

import com.forgeai.testing.domain.model.TestSuite;

import java.util.Optional;
import java.util.UUID;

public interface TestSuiteRepository {
    TestSuite save(TestSuite suite);
    Optional<TestSuite> findByIdAndOrganizationId(UUID id, UUID organizationId);
    Optional<TestSuite> findByOrganizationIdAndRepositoryIdAndName(UUID organizationId, UUID repositoryId, String name);
}
