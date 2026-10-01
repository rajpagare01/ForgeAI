package com.forgeai.identity.infrastructure.adapter.out.persistence.repository;

import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.RoleJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface RoleRepository extends JpaRepository<RoleJpaEntity, UUID> {
    java.util.List<com.forgeai.identity.infrastructure.adapter.out.persistence.entity.RoleJpaEntity> findByOrganizationId(java.util.UUID organizationId);
    java.util.Optional<com.forgeai.identity.infrastructure.adapter.out.persistence.entity.RoleJpaEntity> findByOrganizationIdIsNullAndName(String name);
    java.util.Optional<com.forgeai.identity.infrastructure.adapter.out.persistence.entity.RoleJpaEntity> findByOrganizationIdAndName(java.util.UUID organizationId, String name);
}

