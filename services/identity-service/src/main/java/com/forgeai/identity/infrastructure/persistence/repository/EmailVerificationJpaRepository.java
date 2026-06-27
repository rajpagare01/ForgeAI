package com.forgeai.identity.infrastructure.persistence.repository;

import com.forgeai.identity.infrastructure.persistence.entity.EmailVerificationTokenJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailVerificationJpaRepository extends JpaRepository<EmailVerificationTokenJpaEntity, UUID> {
    Optional<EmailVerificationTokenJpaEntity> findByToken(String token);
}
