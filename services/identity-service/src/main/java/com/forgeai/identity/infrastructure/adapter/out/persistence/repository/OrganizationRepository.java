package com.forgeai.identity.infrastructure.adapter.out.persistence.repository;

import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.OrganizationJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface OrganizationRepository extends JpaRepository<OrganizationJpaEntity, UUID> {
    java.util.Optional<OrganizationJpaEntity> findBySlug(String slug);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT o FROM OrganizationJpaEntity o WHERE o.id = :id")
    java.util.Optional<OrganizationJpaEntity> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
}
