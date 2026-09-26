package com.forgeai.identity.infrastructure.adapter.out.persistence.mapper;

import com.forgeai.identity.domain.model.Role;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.RoleJpaEntity;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

@Component
public class RoleMapper {
    public Role toDomain(RoleJpaEntity entity) {
        if (entity == null) return null;
        Role domain = new Role();
        BeanUtils.copyProperties(entity, domain);
        return domain;
    }

    public RoleJpaEntity toEntity(Role domain) {
        if (domain == null) return null;
        RoleJpaEntity entity = new RoleJpaEntity();
        BeanUtils.copyProperties(domain, entity);
        return entity;
    }
}
