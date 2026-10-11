package com.forgeai.identity.infrastructure.adapter.out.persistence.repository;

import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.EmailVerificationTokenJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationTokenJpaEntity, UUID> {
    Optional<EmailVerificationTokenJpaEntity> findByTokenHash(String tokenHash);
}
