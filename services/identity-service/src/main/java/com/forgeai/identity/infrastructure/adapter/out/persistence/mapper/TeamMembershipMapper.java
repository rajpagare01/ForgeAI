package com.forgeai.identity.infrastructure.adapter.out.persistence.mapper;

import com.forgeai.identity.domain.model.TeamMembership;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.TeamMembershipJpaEntity;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

@Component
public class TeamMembershipMapper {
    public TeamMembership toDomain(TeamMembershipJpaEntity entity) {
        if (entity == null) return null;
        TeamMembership domain = new TeamMembership();
        BeanUtils.copyProperties(entity, domain);
        return domain;
    }

    public TeamMembershipJpaEntity toEntity(TeamMembership domain) {
        if (domain == null) return null;
        TeamMembershipJpaEntity entity = new TeamMembershipJpaEntity();
        BeanUtils.copyProperties(domain, entity);
        return entity;
    }
}
