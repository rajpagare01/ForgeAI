package com.forgeai.project.application.service;

import com.forgeai.project.application.command.CreateProjectCommand;
import com.forgeai.project.domain.event.ProjectCreatedEvent;
import com.forgeai.project.domain.exception.DuplicateResourceException;
import com.forgeai.project.domain.exception.ResourceNotFoundException;
import com.forgeai.project.domain.model.Project;
import com.forgeai.project.domain.model.ProjectStatus;
import com.forgeai.project.domain.repository.ProjectRepository;
import com.forgeai.project.infrastructure.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectApplicationService {

    private final ProjectRepository projectRepository;
    private final OutboxService outboxService;

    @Transactional
    public Project createProject(UUID organizationId, CreateProjectCommand command) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must be provided via tenant context");
        }

        if (projectRepository.existsByOrganizationIdAndKey(organizationId, command.key())) {
            throw new DuplicateResourceException("Project with key '" + command.key() + "' already exists in organization " + organizationId);
        }

        Instant now = Instant.now();
        Project project = Project.builder()
                .organizationId(organizationId)
                .name(command.name())
                .key(command.key().toUpperCase())
                .description(command.description())
                .status(ProjectStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();

        Project saved = projectRepository.save(project);

        // Transactional outbox event
        ProjectCreatedEvent event = ProjectCreatedEvent.builder()
                .projectId(saved.getId())
                .organizationId(organizationId)
                .key(saved.getKey())
                .name(saved.getName())
                .timestamp(now)
                .build();
        outboxService.publish(event, "trace-proj-" + saved.getId());

        log.info("Created project: id={}, key={}, orgId={}", saved.getId(), saved.getKey(), organizationId);
        return saved;
    }

    @Transactional(readOnly = true)
    public Project getProjectById(UUID organizationId, UUID projectId) {
        return projectRepository.findByIdAndOrganizationId(projectId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: id=" + projectId + " in org=" + organizationId));
    }

    @Transactional(readOnly = true)
    public List<Project> listProjects(UUID organizationId) {
        return projectRepository.findAllByOrganizationId(organizationId);
    }
}
