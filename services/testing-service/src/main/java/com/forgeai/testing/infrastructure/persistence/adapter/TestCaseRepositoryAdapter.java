package com.forgeai.testing.infrastructure.persistence.adapter;

import com.forgeai.testing.domain.model.TestCase;
import com.forgeai.testing.domain.repository.TestCaseRepository;
import com.forgeai.testing.infrastructure.persistence.entity.TestCaseJpaEntity;
import com.forgeai.testing.infrastructure.persistence.mapper.TestingEntityDomainMapper;
import com.forgeai.testing.infrastructure.persistence.repository.SpringDataTestCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TestCaseRepositoryAdapter implements TestCaseRepository {

    private final SpringDataTestCaseRepository jpaRepository;
    private final TestingEntityDomainMapper mapper;

    @Override
    public TestCase save(TestCase testCase) {
        if (testCase.getId() != null) {
            Optional<TestCaseJpaEntity> existingOpt = jpaRepository.findById(testCase.getId());
            if (existingOpt.isPresent()) {
                TestCaseJpaEntity entity = existingOpt.get();
                entity.setName(testCase.getName());
                entity.setClassname(testCase.getClassname());
                entity.setRequirementKey(testCase.getRequirementKey());
                entity.setUpdatedAt(Instant.now());
                return mapper.toDomain(jpaRepository.saveAndFlush(entity));
            }
        }
        TestCaseJpaEntity entity = mapper.toEntity(testCase);
        return mapper.toDomain(jpaRepository.saveAndFlush(entity));
    }

    @Override
    public Optional<TestCase> findByIdAndOrganizationId(UUID id, UUID organizationId) {
        return jpaRepository.findByIdAndOrganizationId(id, organizationId).map(mapper::toDomain);
    }

    @Override
    public Optional<TestCase> findBySuiteIdAndNameAndClassname(UUID suiteId, String name, String classname) {
        return jpaRepository.findBySuiteIdAndNameAndClassname(suiteId, name, classname)
                .map(mapper::toDomain);
    }

    @Override
    public List<TestCase> findAllByOrganizationIdAndRequirementKey(UUID organizationId, String requirementKey) {
        return jpaRepository.findAllByOrganizationIdAndRequirementKey(organizationId, requirementKey).stream()
                .map(mapper::toDomain)
                .toList();
    }
}
