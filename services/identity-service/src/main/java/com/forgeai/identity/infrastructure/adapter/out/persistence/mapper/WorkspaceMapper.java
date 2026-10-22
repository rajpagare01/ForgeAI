package com.forgeai.identity.infrastructure.adapter.out.persistence.mapper;

import com.forgeai.identity.domain.model.Workspace;
import com.forgeai.identity.domain.model.WorkspaceType;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.WorkspaceJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceMapper {

    public Workspace toDomain(WorkspaceJpaEntity entity) {
        if (entity == null) {
            return null;
        }

        return Workspace.builder()
                .id(entity.getId())
                .type(entity.getType() != null ? WorkspaceType.valueOf(entity.getType()) : null)
                .ownerUserId(entity.getOwnerUserId())
                .organizationId(entity.getOrganizationId())
                .name(entity.getName())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public WorkspaceJpaEntity toEntity(Workspace domain) {
        if (domain == null) {
            return null;
        }

        return WorkspaceJpaEntity.builder()
                .id(domain.getId())
                .type(domain.getType() != null ? domain.getType().name() : null)
                .ownerUserId(domain.getOwnerUserId())
                .organizationId(domain.getOrganizationId())
                .name(domain.getName())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }
}
