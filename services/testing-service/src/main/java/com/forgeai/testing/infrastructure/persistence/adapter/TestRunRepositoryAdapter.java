package com.forgeai.testing.infrastructure.persistence.adapter;

import com.forgeai.testing.domain.model.TestRun;
import com.forgeai.testing.domain.repository.TestRunRepository;
import com.forgeai.testing.infrastructure.persistence.entity.TestRunJpaEntity;
import com.forgeai.testing.infrastructure.persistence.mapper.TestingEntityDomainMapper;
import com.forgeai.testing.infrastructure.persistence.repository.SpringDataTestRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TestRunRepositoryAdapter implements TestRunRepository {

    private final SpringDataTestRunRepository jpaRepository;
    private final TestingEntityDomainMapper mapper;

    @Override
    public TestRun save(TestRun run) {
        if (run.getId() != null) {
            Optional<TestRunJpaEntity> existingOpt = jpaRepository.findById(run.getId());
            if (existingOpt.isPresent()) {
                TestRunJpaEntity entity = existingOpt.get();
                entity.setStatus(run.getStatus() != null ? run.getStatus().name() : entity.getStatus());
                entity.setTotalTests(run.getTotalTests());
                entity.setPassedTests(run.getPassedTests());
                entity.setFailedTests(run.getFailedTests());
                entity.setSkippedTests(run.getSkippedTests());
                entity.setDurationMs(run.getDurationMs());
                entity.setCompletedAt(run.getCompletedAt());
                return mapper.toDomain(jpaRepository.saveAndFlush(entity));
            }
        }
        TestRunJpaEntity entity = mapper.toEntity(run);
        return mapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<TestRun> findByIdAndOrganizationId(UUID id, UUID organizationId) {
        return jpaRepository.findByIdAndOrganizationId(id, organizationId).map(mapper::toDomain);
    }

    @Override
    public List<TestRun> findAllByOrganizationIdAndCommitSha(UUID organizationId, String commitSha) {
        return jpaRepository.findAllByOrganizationIdAndCommitSha(organizationId, commitSha).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public List<TestRun> findAllByOrganizationIdAndRepositoryIdAndPullRequestNumber(UUID organizationId, UUID repositoryId, Integer pullRequestNumber) {
        return jpaRepository.findAllByOrganizationIdAndRepositoryIdAndPullRequestNumber(organizationId, repositoryId, pullRequestNumber).stream()
                .map(mapper::toDomain)
                .toList();
    }
}
