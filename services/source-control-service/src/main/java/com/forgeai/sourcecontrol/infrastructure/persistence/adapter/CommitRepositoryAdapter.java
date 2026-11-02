package com.forgeai.sourcecontrol.infrastructure.persistence.adapter;

import com.forgeai.sourcecontrol.domain.model.Commit;
import com.forgeai.sourcecontrol.domain.repository.CommitRepository;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.CommitJpaEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.mapper.SourceControlEntityMapper;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataCommitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CommitRepositoryAdapter implements CommitRepository {

    private final SpringDataCommitRepository jpaRepository;
    private final SourceControlEntityMapper mapper;

    @Override
    public Commit save(Commit commit) {
        CommitJpaEntity entity = mapper.toEntity(commit);
        return mapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<Commit> findByRepositoryIdAndSha(UUID repositoryId, String sha) {
        return jpaRepository.findByRepositoryIdAndSha(repositoryId, sha).map(mapper::toDomain);
    }

    @Override
    public boolean existsByRepositoryIdAndSha(UUID repositoryId, String sha) {
        return jpaRepository.existsByRepositoryIdAndSha(repositoryId, sha);
    }

    @Override
    public java.util.List<Commit> findAllByRepositoryId(UUID repositoryId) {
        return jpaRepository.findAllByRepositoryIdOrderByCommittedAtDesc(repositoryId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public java.util.List<Commit> findAllByOrganizationId(UUID organizationId) {
        return jpaRepository.findAllByOrganizationIdOrderByCommittedAtDesc(organizationId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public void deleteAllByRepositoryId(UUID repositoryId) {
        jpaRepository.deleteAllByRepositoryId(repositoryId);
    }
}
