package com.forgeai.sourcecontrol.domain.repository;

import com.forgeai.sourcecontrol.domain.model.Commit;

import java.util.Optional;
import java.util.UUID;

public interface CommitRepository {
    Commit save(Commit commit);
    Optional<Commit> findByRepositoryIdAndSha(UUID repositoryId, String sha);
    boolean existsByRepositoryIdAndSha(UUID repositoryId, String sha);
    java.util.List<Commit> findAllByRepositoryId(UUID repositoryId);
    java.util.List<Commit> findAllByOrganizationId(UUID organizationId);
    void deleteAllByRepositoryId(UUID repositoryId);
}
