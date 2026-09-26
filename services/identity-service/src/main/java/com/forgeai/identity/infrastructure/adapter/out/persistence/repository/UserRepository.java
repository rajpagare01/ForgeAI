package com.forgeai.identity.infrastructure.adapter.out.persistence.repository;

import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.UserJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<UserJpaEntity, UUID> {
    java.util.Optional<UserJpaEntity> findByEmailIgnoreCase(String email);
    java.util.Optional<UserJpaEntity> findByUsernameIgnoreCase(String username);
}
