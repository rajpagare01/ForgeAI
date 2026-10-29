package com.forgeai.sourcecontrol.infrastructure.persistence.repository;

import com.forgeai.sourcecontrol.infrastructure.persistence.entity.GitHubInstallationJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataGitHubInstallationRepository extends JpaRepository<GitHubInstallationJpaEntity, UUID> {
    Optional<GitHubInstallationJpaEntity> findByInstallationId(Long installationId);
    Optional<GitHubInstallationJpaEntity> findFirstByOrganizationIdOrderByUpdatedAtDesc(UUID organizationId);
    Optional<GitHubInstallationJpaEntity> findFirstByWorkspaceIdOrderByUpdatedAtDesc(UUID workspaceId);
    Optional<GitHubInstallationJpaEntity> findFirstByAccountIdOrderByUpdatedAtDesc(Long accountId);
    List<GitHubInstallationJpaEntity> findByAccountLoginIgnoreCase(String accountLogin);
    List<GitHubInstallationJpaEntity> findByStatus(String status);
    List<GitHubInstallationJpaEntity> findByClaimedByUserId(UUID claimedByUserId);
}
