package com.forgeai.project.infrastructure.persistence.repository;

import com.forgeai.project.domain.model.CodeLinkType;
import com.forgeai.project.infrastructure.persistence.entity.CodeLinkJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataCodeLinkRepository extends JpaRepository<CodeLinkJpaEntity, UUID> {
    Optional<CodeLinkJpaEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
    List<CodeLinkJpaEntity> findAllByOrganizationIdAndTaskId(UUID organizationId, UUID taskId);
    List<CodeLinkJpaEntity> findAllByTaskId(UUID taskId);
    boolean existsByTaskIdAndLinkTypeAndExternalRef(UUID taskId, CodeLinkType linkType, String externalRef);
}

