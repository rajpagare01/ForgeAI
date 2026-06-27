package com.forgeai.identity.infrastructure.persistence.repository;

import com.forgeai.identity.infrastructure.persistence.entity.RefreshTokenJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenJpaEntity, UUID> {
    Optional<RefreshTokenJpaEntity> findBySessionId(UUID sessionId);
    void deleteBySessionId(UUID sessionId);
}
