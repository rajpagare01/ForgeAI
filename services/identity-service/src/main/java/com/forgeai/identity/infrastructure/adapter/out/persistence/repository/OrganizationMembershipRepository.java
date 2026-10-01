package com.forgeai.identity.infrastructure.adapter.out.persistence.repository;

import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.OrganizationMembershipJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface OrganizationMembershipRepository extends JpaRepository<OrganizationMembershipJpaEntity, UUID> {
    java.util.Optional<com.forgeai.identity.infrastructure.adapter.out.persistence.entity.OrganizationMembershipJpaEntity> findByUserIdAndOrganizationId(java.util.UUID userId, java.util.UUID organizationId);
    java.util.List<com.forgeai.identity.infrastructure.adapter.out.persistence.entity.OrganizationMembershipJpaEntity> findByOrganizationId(java.util.UUID organizationId);
    java.util.List<com.forgeai.identity.infrastructure.adapter.out.persistence.entity.OrganizationMembershipJpaEntity> findByUserId(java.util.UUID userId);
    long countByOrganizationIdAndRoleId(java.util.UUID organizationId, java.util.UUID roleId);
}

