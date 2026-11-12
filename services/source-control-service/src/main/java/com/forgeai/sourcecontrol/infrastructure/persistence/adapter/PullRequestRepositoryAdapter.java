package com.forgeai.sourcecontrol.infrastructure.persistence.adapter;

import com.forgeai.sourcecontrol.domain.model.PullRequest;
import com.forgeai.sourcecontrol.domain.repository.PullRequestRepository;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.PullRequestJpaEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.mapper.SourceControlEntityMapper;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataPullRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PullRequestRepositoryAdapter implements PullRequestRepository {

    private final SpringDataPullRequestRepository jpaRepository;
    private final SourceControlEntityMapper mapper;

    @Override
    public PullRequest save(PullRequest pullRequest) {
        if (pullRequest.getId() != null) {
            Optional<PullRequestJpaEntity> existingOpt = jpaRepository.findById(pullRequest.getId());
            if (existingOpt.isPresent()) {
                PullRequestJpaEntity entity = existingOpt.get();
                entity.setTitle(pullRequest.getTitle());
                entity.setBody(pullRequest.getBody());
                entity.setSourceBranch(pullRequest.getSourceBranch());
                entity.setTargetBranch(pullRequest.getTargetBranch());
                entity.setHeadSha(pullRequest.getHeadSha());
                entity.setStatus(pullRequest.getStatus() != null ? pullRequest.getStatus().name() : entity.getStatus());
                entity.setUpdatedAt(Instant.now());
                return mapper.toDomain(jpaRepository.saveAndFlush(entity));
            }
        }
        PullRequestJpaEntity entity = mapper.toEntity(pullRequest);
        return mapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<PullRequest> findById(UUID id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<PullRequest> findByRepositoryIdAndNumber(UUID repositoryId, Integer number) {
        return jpaRepository.findByRepositoryIdAndNumber(repositoryId, number).map(mapper::toDomain);
    }

    @Override
    public Optional<PullRequest> findByGithubPrId(Long githubPrId) {
        return jpaRepository.findByGithubPrId(githubPrId).map(mapper::toDomain);
    }

    @Override
    public java.util.List<PullRequest> findAllByOrganizationId(UUID organizationId) {
        return jpaRepository.findAllByOrganizationId(organizationId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public java.util.List<PullRequest> findAllByRepositoryId(UUID repositoryId) {
        return jpaRepository.findAllByRepositoryId(repositoryId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public void deleteAllByRepositoryId(UUID repositoryId) {
        jpaRepository.deleteAllByRepositoryId(repositoryId);
    }
}
