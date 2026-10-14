package com.forgeai.testing.infrastructure.persistence.adapter;

import com.forgeai.testing.domain.model.TestSuite;
import com.forgeai.testing.domain.repository.TestSuiteRepository;
import com.forgeai.testing.infrastructure.persistence.entity.TestSuiteJpaEntity;
import com.forgeai.testing.infrastructure.persistence.mapper.TestingEntityDomainMapper;
import com.forgeai.testing.infrastructure.persistence.repository.SpringDataTestSuiteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TestSuiteRepositoryAdapter implements TestSuiteRepository {

    private final SpringDataTestSuiteRepository jpaRepository;
    private final TestingEntityDomainMapper mapper;

    @Override
    public TestSuite save(TestSuite suite) {
        if (suite.getId() != null) {
            Optional<TestSuiteJpaEntity> existingOpt = jpaRepository.findById(suite.getId());
            if (existingOpt.isPresent()) {
                TestSuiteJpaEntity entity = existingOpt.get();
                entity.setName(suite.getName());
                entity.setFramework(suite.getFramework());
                entity.setUpdatedAt(Instant.now());
                return mapper.toDomain(jpaRepository.saveAndFlush(entity));
            }
        }
        TestSuiteJpaEntity entity = mapper.toEntity(suite);
        return mapper.toDomain(jpaRepository.saveAndFlush(entity));
    }

    @Override
    public Optional<TestSuite> findByIdAndOrganizationId(UUID id, UUID organizationId) {
        return jpaRepository.findByIdAndOrganizationId(id, organizationId).map(mapper::toDomain);
    }

    @Override
    public Optional<TestSuite> findByOrganizationIdAndRepositoryIdAndName(UUID organizationId, UUID repositoryId, String name) {
        return jpaRepository.findByOrganizationIdAndRepositoryIdAndName(organizationId, repositoryId, name)
                .map(mapper::toDomain);
    }
}
