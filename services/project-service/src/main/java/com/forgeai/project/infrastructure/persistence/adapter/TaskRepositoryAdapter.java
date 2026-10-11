package com.forgeai.project.infrastructure.persistence.adapter;

import com.forgeai.project.domain.model.Task;
import com.forgeai.project.domain.model.TaskStatus;
import com.forgeai.project.domain.repository.TaskRepository;
import com.forgeai.project.infrastructure.persistence.entity.TaskJpaEntity;
import com.forgeai.project.infrastructure.persistence.mapper.EntityDomainMapper;
import com.forgeai.project.infrastructure.persistence.repository.SpringDataTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class TaskRepositoryAdapter implements TaskRepository {

    private final SpringDataTaskRepository springDataRepository;

    @Override
    public Task save(Task task) {
        if (task.getId() != null) {
            Optional<TaskJpaEntity> existingOpt = springDataRepository.findById(task.getId());
            if (existingOpt.isPresent()) {
                TaskJpaEntity existing = existingOpt.get();
                existing.setStatus(task.getStatus());
                existing.setPriority(task.getPriority());
                existing.setTitle(task.getTitle());
                existing.setDescription(task.getDescription());
                existing.setAssigneeId(task.getAssigneeId());
                existing.setRequirementId(task.getRequirementId());
                existing.setUpdatedAt(task.getUpdatedAt() != null ? task.getUpdatedAt() : java.time.Instant.now());
                TaskJpaEntity saved = springDataRepository.saveAndFlush(existing);
                return EntityDomainMapper.toDomain(saved);
            }
        }
        TaskJpaEntity entity = EntityDomainMapper.toEntity(task);
        TaskJpaEntity saved = springDataRepository.saveAndFlush(entity);
        return EntityDomainMapper.toDomain(saved);
    }

    @Override
    public Optional<Task> findByIdAndOrganizationId(UUID id, UUID organizationId) {
        return springDataRepository.findByIdAndOrganizationId(id, organizationId)
                .map(EntityDomainMapper::toDomain);
    }

    @Override
    public Optional<Task> findByProjectIdAndTaskKey(UUID projectId, String taskKey) {
        return springDataRepository.findByProjectIdAndTaskKey(projectId, taskKey)
                .map(EntityDomainMapper::toDomain);
    }

    @Override
    public Optional<Task> findByOrganizationIdAndTaskKey(UUID organizationId, String taskKey) {
        return springDataRepository.findByOrganizationIdAndTaskKey(organizationId, taskKey)
                .map(EntityDomainMapper::toDomain);
    }

    @Override
    public List<Task> findAllByOrganizationIdAndProjectId(UUID organizationId, UUID projectId) {
        return springDataRepository.findAllByOrganizationIdAndProjectId(organizationId, projectId).stream()
                .map(EntityDomainMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<Task> findAllByOrganizationIdAndProjectIdAndStatus(UUID organizationId, UUID projectId, TaskStatus status) {
        return springDataRepository.findAllByOrganizationIdAndProjectIdAndStatus(organizationId, projectId, status).stream()
                .map(EntityDomainMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<Task> findAllByOrganizationIdAndAssigneeId(UUID organizationId, UUID assigneeId) {
        return springDataRepository.findAllByOrganizationIdAndAssigneeId(organizationId, assigneeId).stream()
                .map(EntityDomainMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public boolean existsByProjectIdAndTaskKey(UUID projectId, String taskKey) {
        return springDataRepository.existsByProjectIdAndTaskKey(projectId, taskKey);
    }
}
