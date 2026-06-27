package com.forgeai.identity.infrastructure.persistence.repository;

import com.forgeai.identity.infrastructure.persistence.entity.OutboxEventJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface OutboxJpaRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {
}
