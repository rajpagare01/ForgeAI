package com.forgeai.identity.infrastructure.adapter.out.persistence.repository;

import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.TeamJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TeamRepository extends JpaRepository<TeamJpaEntity, UUID> {
    java.util.List<com.forgeai.identity.infrastructure.adapter.out.persistence.entity.TeamJpaEntity> findByOrganizationId(java.util.UUID organizationId);
    java.util.Optional<com.forgeai.identity.infrastructure.adapter.out.persistence.entity.TeamJpaEntity> findByOrganizationIdAndName(java.util.UUID organizationId, String name);
}

