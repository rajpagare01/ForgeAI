package com.forgeai.sourcecontrol.infrastructure.persistence.adapter;

import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.GitHubInstallationJpaEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.mapper.SourceControlEntityMapper;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataGitHubInstallationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class GitHubInstallationRepositoryAdapter implements GitHubInstallationRepository {

    private final SpringDataGitHubInstallationRepository jpaRepository;
    private final SourceControlEntityMapper mapper;

    @Override
    public GitHubInstallation save(GitHubInstallation installation) {
        if (installation.getId() != null) {
            Optional<GitHubInstallationJpaEntity> existingOpt = jpaRepository.findById(installation.getId());
            if (existingOpt.isPresent()) {
                GitHubInstallationJpaEntity entity = existingOpt.get();
                entity.setOrganizationId(installation.getOrganizationId());
                entity.setWorkspaceId(installation.getWorkspaceId());
                entity.setScope(installation.getScope());
                entity.setAccountLogin(installation.getAccountLogin());
                entity.setAccountType(installation.getAccountType());
                entity.setStatus(installation.getStatus() != null ? installation.getStatus().name() : entity.getStatus());
                entity.setUpdatedAt(Instant.now());
                return mapper.toDomain(jpaRepository.saveAndFlush(entity));
            }
        }
        GitHubInstallationJpaEntity entity = mapper.toEntity(installation);
        return mapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<GitHubInstallation> findByInstallationId(Long installationId) {
        return jpaRepository.findByInstallationId(installationId).map(mapper::toDomain);
    }

    @Override
    public Optional<GitHubInstallation> findByOrganizationId(UUID organizationId) {
        return jpaRepository.findByOrganizationId(organizationId).map(mapper::toDomain);
    }

    @Override
    public Optional<GitHubInstallation> findByWorkspaceId(UUID workspaceId) {
        return jpaRepository.findByWorkspaceId(workspaceId).map(mapper::toDomain);
    }

    @Override
    public List<GitHubInstallation> findAll() {
        return jpaRepository.findAll().stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }
}

