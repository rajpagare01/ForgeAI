package com.forgeai.sourcecontrol.infrastructure.persistence.adapter;

import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.domain.repository.RepositoryRepository;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.RepositoryJpaEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.mapper.SourceControlEntityMapper;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataRepositoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class RepositoryRepositoryAdapter implements RepositoryRepository {

    private final SpringDataRepositoryRepository jpaRepository;
    private final SourceControlEntityMapper mapper;

    public RepositoryRepositoryAdapter(SpringDataRepositoryRepository jpaRepository, SourceControlEntityMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public Repository save(Repository repository) {
        if (repository.getId() != null) {
            Optional<RepositoryJpaEntity> existingOpt = jpaRepository.findById(repository.getId());
            if (existingOpt.isPresent()) {
                RepositoryJpaEntity entity = existingOpt.get();
                entity.setName(repository.getName());
                entity.setFullName(repository.getFullName());
                entity.setWorkspaceId(repository.getWorkspaceId());
                entity.setScope(repository.getScope());
                entity.setDefaultBranch(repository.getDefaultBranch());
                entity.setIsPrivate(repository.getIsPrivate());
                entity.setLatestObservedSha(repository.getLatestObservedSha());
                entity.setLatestIndexedSha(repository.getLatestIndexedSha());
                entity.setSyncStatus(repository.getSyncStatus() != null ? repository.getSyncStatus().name() : "SYNCED");
                entity.setLastSyncedAt(repository.getLastSyncedAt());
                entity.setLastError(repository.getLastError());
                entity.setLastDeliveryId(repository.getLastDeliveryId());
                entity.setUpdatedAt(Instant.now());
                return mapper.toDomain(jpaRepository.saveAndFlush(entity));
            }
        }
        RepositoryJpaEntity entity = mapper.toEntity(repository);
        return mapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<Repository> findById(UUID id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<Repository> findByIdAndOrganizationId(UUID id, UUID organizationId) {
        return jpaRepository.findByIdAndOrganizationId(id, organizationId).map(mapper::toDomain);
    }

    @Override
    public Optional<Repository> findByIdAndWorkspaceId(UUID id, UUID workspaceId) {
        return jpaRepository.findByIdAndWorkspaceId(id, workspaceId).map(mapper::toDomain);
    }

    @Override
    public Optional<Repository> findByGithubRepoId(Long githubRepoId) {
        return jpaRepository.findByGithubRepoId(githubRepoId).map(mapper::toDomain);
    }

    @Override
    public List<Repository> findAllByOrganizationId(UUID organizationId) {
        return jpaRepository.findAllByOrganizationId(organizationId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public List<Repository> findAllByWorkspaceId(UUID workspaceId) {
        return jpaRepository.findAllByWorkspaceId(workspaceId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public List<Repository> findAllByInstallationId(Long installationId) {
        return jpaRepository.findAllByInstallationId(installationId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public List<Repository> findAll() {
        return jpaRepository.findAll().stream()
                .map(mapper::toDomain)
                .toList();
    }
}

