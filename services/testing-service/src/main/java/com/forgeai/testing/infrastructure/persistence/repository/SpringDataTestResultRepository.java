package com.forgeai.testing.infrastructure.persistence.repository;

import com.forgeai.testing.infrastructure.persistence.entity.TestResultJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SpringDataTestResultRepository extends JpaRepository<TestResultJpaEntity, UUID> {
    List<TestResultJpaEntity> findAllByOrganizationIdAndTestRunId(UUID organizationId, UUID testRunId);
    List<TestResultJpaEntity> findAllByOrganizationIdAndTestCaseId(UUID organizationId, UUID testCaseId);

    @Query("""
        SELECT r FROM TestResultJpaEntity r
        WHERE r.organizationId = :organizationId
          AND r.testCaseId = :testCaseId
        ORDER BY r.createdAt DESC
        LIMIT 1
    """)
    List<TestResultJpaEntity> findLatestByTestCaseId(@Param("organizationId") UUID organizationId,
                                                    @Param("testCaseId") UUID testCaseId);
}
