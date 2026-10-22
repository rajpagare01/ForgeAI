package com.forgeai.identity.api.dto;

import com.forgeai.identity.domain.model.Workspace;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceResponse(
        UUID id,
        String type,
        String name,
        UUID ownerUserId,
        UUID organizationId,
        Instant createdAt,
        Instant updatedAt
) {
    public static WorkspaceResponse from(Workspace domain) {
        if (domain == null) {
            return null;
        }
        return new WorkspaceResponse(
                domain.getId(),
                domain.getType() != null ? domain.getType().name() : null,
                domain.getName(),
                domain.getOwnerUserId(),
                domain.getOrganizationId(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }
}
