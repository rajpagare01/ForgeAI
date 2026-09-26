package com.forgeai.identity.infrastructure.adapter.out.persistence.mapper;

import com.forgeai.identity.domain.model.Team;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.TeamJpaEntity;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

@Component
public class TeamMapper {
    public Team toDomain(TeamJpaEntity entity) {
        if (entity == null) return null;
        Team domain = new Team();
        BeanUtils.copyProperties(entity, domain);
        return domain;
    }

    public TeamJpaEntity toEntity(Team domain) {
        if (domain == null) return null;
        TeamJpaEntity entity = new TeamJpaEntity();
        BeanUtils.copyProperties(domain, entity);
        return entity;
    }
}
