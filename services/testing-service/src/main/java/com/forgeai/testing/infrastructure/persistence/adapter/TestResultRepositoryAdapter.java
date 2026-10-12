package com.forgeai.testing.infrastructure.persistence.adapter;

import com.forgeai.testing.domain.model.TestResult;
import com.forgeai.testing.domain.repository.TestResultRepository;
import com.forgeai.testing.infrastructure.persistence.batch.JdbcBatchTestResultRepository;
import com.forgeai.testing.infrastructure.persistence.entity.TestResultJpaEntity;
import com.forgeai.testing.infrastructure.persistence.mapper.TestingEntityDomainMapper;
import com.forgeai.testing.infrastructure.persistence.repository.SpringDataTestResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TestResultRepositoryAdapter implements TestResultRepository {

    private final SpringDataTestResultRepository jpaRepository;
    private final JdbcBatchTestResultRepository jdbcBatchRepository;
    private final TestingEntityDomainMapper mapper;

    @Override
    public TestResult save(TestResult result) {
        TestResultJpaEntity entity = mapper.toEntity(result);
        return mapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public List<TestResult> saveAll(List<TestResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        return jdbcBatchRepository.batchInsert(results, 500);
    }

    @Override
    public List<TestResult> findAllByOrganizationIdAndTestRunId(UUID organizationId, UUID testRunId) {
        return jpaRepository.findAllByOrganizationIdAndTestRunId(organizationId, testRunId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public List<TestResult> findAllByOrganizationIdAndTestCaseId(UUID organizationId, UUID testCaseId) {
        return jpaRepository.findAllByOrganizationIdAndTestCaseId(organizationId, testCaseId).stream()
                .map(mapper::toDomain)
                .toList();
    }
}
