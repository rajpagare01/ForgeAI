package com.forgeai.identity.infrastructure.persistence.repository;

import com.forgeai.identity.infrastructure.persistence.entity.SessionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface SessionJpaRepository extends JpaRepository<SessionJpaEntity, UUID> {
    List<SessionJpaEntity> findByUserIdAndStatus(UUID userId, String status);
    
    @Modifying
    @Query("DELETE FROM SessionJpaEntity s WHERE s.status = 'EXPIRED' OR (s.status = 'ACTIVE' AND s.lastAccessed < :threshold)")
    void deleteExpiredSessions(Instant threshold);
}
