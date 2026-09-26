package com.forgeai.identity.infrastructure.adapter.out.persistence.mapper;

import com.forgeai.identity.domain.model.Permission;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.PermissionJpaEntity;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

@Component
public class PermissionMapper {
    public Permission toDomain(PermissionJpaEntity entity) {
        if (entity == null) return null;
        Permission domain = new Permission();
        BeanUtils.copyProperties(entity, domain);
        return domain;
    }

    public PermissionJpaEntity toEntity(Permission domain) {
        if (domain == null) return null;
        PermissionJpaEntity entity = new PermissionJpaEntity();
        BeanUtils.copyProperties(domain, entity);
        return entity;
    }
}
