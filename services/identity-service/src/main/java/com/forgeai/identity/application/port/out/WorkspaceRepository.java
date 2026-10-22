package com.forgeai.identity.application.port.out;

import com.forgeai.identity.domain.model.Workspace;
import com.forgeai.identity.domain.model.WorkspaceType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository {
    Optional<Workspace> findById(UUID id);
    Optional<Workspace> findByTypeAndOwnerUserId(WorkspaceType type, UUID ownerUserId);
    Optional<Workspace> findByTypeAndOrganizationId(WorkspaceType type, UUID organizationId);
    List<Workspace> findByOwnerUserId(UUID ownerUserId);
    List<Workspace> findByTypeAndOrganizationIdIn(WorkspaceType type, Collection<UUID> organizationIds);
    Workspace save(Workspace workspace);
    void deleteById(UUID id);
}
