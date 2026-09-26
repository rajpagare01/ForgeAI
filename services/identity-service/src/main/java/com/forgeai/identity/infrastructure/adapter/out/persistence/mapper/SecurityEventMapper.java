package com.forgeai.identity.infrastructure.adapter.out.persistence.mapper;

import com.forgeai.identity.domain.model.SecurityEvent;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.SecurityEventJpaEntity;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

@Component
public class SecurityEventMapper {
    public SecurityEvent toDomain(SecurityEventJpaEntity entity) {
        if (entity == null) return null;
        SecurityEvent domain = new SecurityEvent();
        BeanUtils.copyProperties(entity, domain);
        return domain;
    }

    public SecurityEventJpaEntity toEntity(SecurityEvent domain) {
        if (domain == null) return null;
        SecurityEventJpaEntity entity = new SecurityEventJpaEntity();
        BeanUtils.copyProperties(domain, entity);
        return entity;
    }
}
