package com.forgeai.identity.infrastructure.adapter.out.persistence.repository;

import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.TeamMembershipJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TeamMembershipRepository extends JpaRepository<TeamMembershipJpaEntity, UUID> {
    java.util.Optional<com.forgeai.identity.infrastructure.adapter.out.persistence.entity.TeamMembershipJpaEntity> findByTeamIdAndUserId(java.util.UUID teamId, java.util.UUID userId);
    java.util.List<com.forgeai.identity.infrastructure.adapter.out.persistence.entity.TeamMembershipJpaEntity> findByTeamId(java.util.UUID teamId);
    java.util.List<com.forgeai.identity.infrastructure.adapter.out.persistence.entity.TeamMembershipJpaEntity> findByUserId(java.util.UUID userId);
}

