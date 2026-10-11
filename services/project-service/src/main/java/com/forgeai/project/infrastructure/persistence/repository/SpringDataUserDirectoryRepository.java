package com.forgeai.project.infrastructure.persistence.repository;

import com.forgeai.project.infrastructure.persistence.entity.UserDirectoryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataUserDirectoryRepository extends JpaRepository<UserDirectoryJpaEntity, UUID> {
    Optional<UserDirectoryJpaEntity> findByEmail(String email);
}
