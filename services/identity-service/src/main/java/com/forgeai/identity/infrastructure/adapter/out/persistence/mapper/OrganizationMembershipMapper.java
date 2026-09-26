package com.forgeai.identity.infrastructure.adapter.out.persistence.mapper;

import com.forgeai.identity.domain.model.OrganizationMembership;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.OrganizationMembershipJpaEntity;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

@Component
public class OrganizationMembershipMapper {
    public OrganizationMembership toDomain(OrganizationMembershipJpaEntity entity) {
        if (entity == null) return null;
        OrganizationMembership domain = new OrganizationMembership();
        BeanUtils.copyProperties(entity, domain);
        return domain;
    }

    public OrganizationMembershipJpaEntity toEntity(OrganizationMembership domain) {
        if (domain == null) return null;
        OrganizationMembershipJpaEntity entity = new OrganizationMembershipJpaEntity();
        BeanUtils.copyProperties(domain, entity);
        return entity;
    }
}
