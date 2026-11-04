package com.forgeai.project.domain.repository;

import com.forgeai.project.domain.model.Task;
import com.forgeai.project.domain.model.TaskStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository {
    Task save(Task task);
    Optional<Task> findById(UUID id);
    Optional<Task> findByIdAndOrganizationId(UUID id, UUID organizationId);
    Optional<Task> findByProjectIdAndTaskKey(UUID projectId, String taskKey);
    Optional<Task> findByOrganizationIdAndTaskKey(UUID organizationId, String taskKey);
    Optional<Task> findByOrganizationIdAndTaskKeyIgnoreCase(UUID organizationId, String taskKey);
    Optional<Task> findByWorkspaceIdAndTaskKeyIgnoreCase(UUID workspaceId, String taskKey);
    List<Task> findAllByTaskKeyIgnoreCase(String taskKey);
    List<Task> findAllByOrganizationIdAndProjectId(UUID organizationId, UUID projectId);
    List<Task> findAllByOrganizationIdAndProjectIdAndStatus(UUID organizationId, UUID projectId, TaskStatus status);
    List<Task> findAllByProjectId(UUID projectId);
    List<Task> findAllByProjectIdAndStatus(UUID projectId, TaskStatus status);
    List<Task> findAllByOrganizationIdAndAssigneeId(UUID organizationId, UUID assigneeId);
    boolean existsByProjectIdAndTaskKey(UUID projectId, String taskKey);
}
