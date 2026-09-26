package com.forgeai.identity.infrastructure.adapter.out.persistence.mapper;

import com.forgeai.identity.domain.model.Session;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.SessionJpaEntity;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

@Component
public class SessionMapper {
    public Session toDomain(SessionJpaEntity entity) {
        if (entity == null) return null;
        Session domain = new Session();
        BeanUtils.copyProperties(entity, domain);
        return domain;
    }

    public SessionJpaEntity toEntity(Session domain) {
        if (domain == null) return null;
        SessionJpaEntity entity = new SessionJpaEntity();
        BeanUtils.copyProperties(domain, entity);
        return entity;
    }
}
