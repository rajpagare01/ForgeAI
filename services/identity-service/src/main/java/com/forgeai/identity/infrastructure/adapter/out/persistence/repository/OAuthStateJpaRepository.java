package com.forgeai.identity.infrastructure.adapter.out.persistence.repository;

import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.OAuthStateJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface OAuthStateJpaRepository extends JpaRepository<OAuthStateJpaEntity, String> {
    Optional<OAuthStateJpaEntity> findByState(String state);

    @Modifying
    @Query("DELETE FROM OAuthStateJpaEntity o WHERE o.expiresAt < :now OR o.used = true")
    void cleanupExpiredStates(Instant now);
}
