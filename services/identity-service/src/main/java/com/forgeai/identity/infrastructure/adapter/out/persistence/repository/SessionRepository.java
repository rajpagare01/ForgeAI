package com.forgeai.identity.infrastructure.adapter.out.persistence.repository;

import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.SessionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface SessionRepository extends JpaRepository<SessionJpaEntity, UUID> {
    List<SessionJpaEntity> findByUserIdAndRevokedAtIsNull(UUID userId);

    @Modifying
    @Query("UPDATE SessionJpaEntity s SET s.revokedAt = :revokedAt WHERE s.id = :sessionId AND s.revokedAt IS NULL")
    void revokeById(@Param("sessionId") UUID sessionId, @Param("revokedAt") Instant revokedAt);
}
