package com.forgeai.identity.infrastructure.adapter.out.persistence.mapper;

import com.forgeai.identity.domain.model.Organization;
import com.forgeai.identity.domain.model.OrganizationStatus;
import com.forgeai.identity.domain.valueobject.OrganizationSlug;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.OrganizationJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class OrganizationMapper {

    public Organization toDomain(OrganizationJpaEntity entity) {
        if (entity == null) {
            return null;
        }

        Organization org = new Organization();
        org.setId(entity.getId());
        org.setName(entity.getName());
        org.setSlug(new OrganizationSlug(entity.getSlug()));
        org.setDescription(entity.getDescription());

        if (entity.getStatus() != null) {
            org.setStatus(OrganizationStatus.valueOf(entity.getStatus()));
        }

        org.setCreatedAt(entity.getCreatedAt());
        org.setUpdatedAt(entity.getUpdatedAt());

        return org;
    }

    public OrganizationJpaEntity toEntity(Organization domain) {
        if (domain == null) {
            return null;
        }

        OrganizationJpaEntity entity = new OrganizationJpaEntity();
        entity.setId(domain.getId());
        entity.setName(domain.getName());
        if (domain.getSlug() != null) {
            entity.setSlug(domain.getSlug().value());
        }
        entity.setDescription(domain.getDescription());

        if (domain.getStatus() != null) {
            entity.setStatus(domain.getStatus().name());
        }

        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());

        return entity;
    }
}
