package com.forgeai.identity.infrastructure.adapter.out.persistence.repository;

import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.InvitationJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvitationRepository extends JpaRepository<InvitationJpaEntity, UUID> {
    Optional<InvitationJpaEntity> findByToken(String token);
    List<InvitationJpaEntity> findByOrganizationId(UUID organizationId);
    List<InvitationJpaEntity> findByOrganizationIdAndEmail(UUID organizationId, String email);
}
