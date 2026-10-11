package com.forgeai.project.infrastructure.persistence.repository;

import com.forgeai.project.domain.model.TaskStatus;
import com.forgeai.project.infrastructure.persistence.entity.TaskJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataTaskRepository extends JpaRepository<TaskJpaEntity, UUID> {
    Optional<TaskJpaEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
    Optional<TaskJpaEntity> findByProjectIdAndTaskKey(UUID projectId, String taskKey);
    Optional<TaskJpaEntity> findByOrganizationIdAndTaskKey(UUID organizationId, String taskKey);
    List<TaskJpaEntity> findAllByOrganizationIdAndProjectId(UUID organizationId, UUID projectId);
    List<TaskJpaEntity> findAllByOrganizationIdAndProjectIdAndStatus(UUID organizationId, UUID projectId, TaskStatus status);
    List<TaskJpaEntity> findAllByOrganizationIdAndAssigneeId(UUID organizationId, UUID assigneeId);
    boolean existsByProjectIdAndTaskKey(UUID projectId, String taskKey);
}
