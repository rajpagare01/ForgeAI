package com.forgeai.project.application.service;

import com.forgeai.project.application.command.CreateTaskCommand;
import com.forgeai.project.application.command.LinkCodeArtifactCommand;
import com.forgeai.project.application.command.UpdateTaskStatusCommand;
import com.forgeai.project.domain.event.CodeLinkedEvent;
import com.forgeai.project.domain.event.TaskCreatedEvent;
import com.forgeai.project.domain.event.TaskStatusChangedEvent;
import com.forgeai.project.domain.exception.*;
import com.forgeai.project.domain.model.*;
import com.forgeai.project.domain.repository.*;
import com.forgeai.project.infrastructure.outbox.OutboxService;
import com.forgeai.project.infrastructure.persistence.entity.WorkspaceJpaEntity;
import com.forgeai.project.infrastructure.persistence.repository.SpringDataWorkspaceRepository;
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
public class TaskApplicationService {

    private final ProjectRepository projectRepository;
    private final RequirementRepository requirementRepository;
    private final TaskRepository taskRepository;
    private final CodeLinkRepository codeLinkRepository;
    private final UserDirectoryRepository userDirectoryRepository;
    private final OutboxService outboxService;
    private final SpringDataWorkspaceRepository workspaceRepository;

    @Transactional
    public Task createTask(UUID organizationId, UUID projectId, UUID reporterId, CreateTaskCommand command) {
        if (reporterId == null) {
            throw new IllegalArgumentException("Reporter ID must be provided via tenant context");
        }

        // 1. Validate project
        Project project = resolveProject(organizationId, projectId, reporterId);
        UUID effectiveOrgId = project.getOrganizationId();

        // 2. Validate requirement if supplied and ensure it belongs to the SAME project and workspace
        if (command.requirementId() != null) {
            Requirement req;
            if (effectiveOrgId != null) {
                req = requirementRepository.findByIdAndOrganizationId(command.requirementId(), effectiveOrgId)
                        .orElseThrow(() -> new ResourceNotFoundException("Requirement not found: id=" + command.requirementId() + " in org=" + effectiveOrgId));
            } else {
                req = requirementRepository.findById(command.requirementId())
                        .orElseThrow(() -> new ResourceNotFoundException("Requirement not found: id=" + command.requirementId()));
            }
            if (project.getWorkspaceId() != null && req.getWorkspaceId() != null && !project.getWorkspaceId().equals(req.getWorkspaceId())) {
                throw new IllegalArgumentException("Requirement " + command.requirementId() + " does not belong to the active project workspace.");
            }
            if (!req.getProjectId().equals(projectId)) {
                throw new IllegalArgumentException("Requirement " + command.requirementId() + " belongs to project " + req.getProjectId() + ", not " + projectId);
            }
        }

        // 3. Unique task key within project
        if (taskRepository.existsByProjectIdAndTaskKey(projectId, command.taskKey())) {
            throw new DuplicateResourceException("Task with key '" + command.taskKey() + "' already exists in project " + projectId);
        }

        // 4. Validate assignee against user_directory
        if (command.assigneeId() != null) {
            validateAssignee(command.assigneeId());
        }

        Instant now = Instant.now();
        TaskPriority priority = command.priority() != null ? command.priority() : TaskPriority.MEDIUM;
        Task task = Task.builder()
                .projectId(project.getId())
                .workspaceId(project.getWorkspaceId())
                .organizationId(effectiveOrgId)
                .requirementId(command.requirementId())
                .taskKey(command.taskKey().toUpperCase())
                .title(command.title())
                .description(command.description())
                .status(TaskStatus.TODO)
                .priority(priority)
                .assigneeId(command.assigneeId())
                .reporterId(reporterId)
                .version(1)
                .createdAt(now)
                .updatedAt(now)
                .build();

        Task saved = taskRepository.save(task);

        // Outbox event
        TaskCreatedEvent event = TaskCreatedEvent.builder()
                .taskId(saved.getId())
                .projectId(saved.getProjectId())
                .workspaceId(saved.getWorkspaceId())
                .organizationId(effectiveOrgId != null ? effectiveOrgId : UUID.fromString("00000000-0000-0000-0000-000000000000"))
                .taskKey(saved.getTaskKey())
                .title(saved.getTitle())
                .status(saved.getStatus())
                .priority(saved.getPriority())
                .assigneeId(saved.getAssigneeId())
                .reporterId(saved.getReporterId())
                .timestamp(now)
                .build();
        outboxService.publish(event, "trace-task-" + saved.getId());

        log.info("Created task: id={}, key={}, projectId={}", saved.getId(), saved.getTaskKey(), projectId);
        return saved;
    }

    @Transactional
    public Task updateTaskStatus(UUID organizationId, UUID taskId, UpdateTaskStatusCommand command) {
        return updateTaskStatus(organizationId, taskId, null, command);
    }

    @Transactional
    public Task updateTaskStatus(UUID organizationId, UUID taskId, UUID userId, UpdateTaskStatusCommand command) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: id=" + taskId));
        resolveProject(organizationId, task.getProjectId(), userId);

        if (command.version() != null && !command.version().equals(task.getVersion())) {
            throw new jakarta.persistence.OptimisticLockException(
                    "Task " + taskId + " has version " + task.getVersion() + ", but command had " + command.version());
        }

        TaskStatus currentStatus = task.getStatus();
        TaskStatus targetStatus = command.status();

        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new InvalidTaskStatusTransitionException(
                    "Invalid state transition from " + currentStatus + " to " + targetStatus);
        }

        task.setStatus(targetStatus);
        task.setUpdatedAt(Instant.now());

        Task updated = taskRepository.save(task);

        // Outbox event
        TaskStatusChangedEvent event = TaskStatusChangedEvent.builder()
                .taskId(updated.getId())
                .projectId(updated.getProjectId())
                .workspaceId(updated.getWorkspaceId())
                .organizationId(updated.getOrganizationId() != null ? updated.getOrganizationId() : UUID.fromString("00000000-0000-0000-0000-000000000000"))
                .taskKey(updated.getTaskKey())
                .oldStatus(currentStatus)
                .newStatus(targetStatus)
                .timestamp(Instant.now())
                .build();
        outboxService.publish(event, "trace-task-status-" + updated.getId());

        log.info("Updated task status: id={}, from={}, to={}", taskId, currentStatus, targetStatus);
        return updated;
    }

    @Transactional
    public CodeLink linkCodeArtifact(UUID organizationId, UUID taskId, LinkCodeArtifactCommand command) {
        return linkCodeArtifact(organizationId, taskId, null, command);
    }

    @Transactional
    public CodeLink linkCodeArtifact(UUID organizationId, UUID taskId, UUID userId, LinkCodeArtifactCommand command) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: id=" + taskId));
        resolveProject(organizationId, task.getProjectId(), userId);

        if (codeLinkRepository.existsByTaskIdAndLinkTypeAndExternalRef(taskId, command.linkType(), command.externalRef())) {
            throw new DuplicateResourceException("Code link for task " + taskId + " with ref " + command.externalRef() + " already exists");
        }

        Instant now = Instant.now();
        UUID effectiveOrgId = task.getOrganizationId();
        CodeLink codeLink = CodeLink.builder()
                .workspaceId(task.getWorkspaceId())
                .organizationId(effectiveOrgId)
                .taskId(task.getId())
                .linkType(command.linkType())
                .externalRef(command.externalRef())
                .repositoryId(command.repositoryId())
                .createdBy(command.createdBy() != null ? command.createdBy() : "USER")
                .createdAt(now)
                .build();

        CodeLink saved = codeLinkRepository.save(codeLink);

        // Outbox event
        CodeLinkedEvent event = CodeLinkedEvent.builder()
                .codeLinkId(saved.getId())
                .workspaceId(saved.getWorkspaceId())
                .organizationId(effectiveOrgId != null ? effectiveOrgId : UUID.fromString("00000000-0000-0000-0000-000000000000"))
                .taskId(task.getId())
                .linkType(saved.getLinkType())
                .externalRef(saved.getExternalRef())
                .repositoryId(saved.getRepositoryId())
                .timestamp(now)
                .build();
        outboxService.publish(event, "trace-codelink-" + saved.getId());

        log.info("Linked code artifact to task: taskId={}, linkType={}, ref={}", taskId, saved.getLinkType(), saved.getExternalRef());
        return saved;
    }

    @Transactional(readOnly = true)
    public Task getTaskById(UUID organizationId, UUID taskId) {
        return getTaskById(organizationId, taskId, null);
    }

    @Transactional(readOnly = true)
    public Task getTaskById(UUID organizationId, UUID taskId, UUID userId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: id=" + taskId));
        resolveProject(organizationId, task.getProjectId(), userId);
        return task;
    }

    @Transactional(readOnly = true)
    public List<Task> listTasks(UUID organizationId, UUID projectId, TaskStatus status) {
        return listTasks(organizationId, projectId, null, status);
    }

    @Transactional(readOnly = true)
    public List<Task> listTasks(UUID organizationId, UUID projectId, UUID userId, TaskStatus status) {
        Project project = resolveProject(organizationId, projectId, userId);
        UUID effectiveOrgId = project.getOrganizationId();
        if (effectiveOrgId != null) {
            if (status != null) {
                return taskRepository.findAllByOrganizationIdAndProjectIdAndStatus(effectiveOrgId, projectId, status);
            }
            return taskRepository.findAllByOrganizationIdAndProjectId(effectiveOrgId, projectId);
        }

        if (status != null) {
            return taskRepository.findAllByProjectIdAndStatus(projectId, status);
        }
        return taskRepository.findAllByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public List<CodeLink> getCodeLinksForTask(UUID organizationId, UUID taskId) {
        return getCodeLinksForTask(organizationId, taskId, null);
    }

    @Transactional(readOnly = true)
    public List<CodeLink> getCodeLinksForTask(UUID organizationId, UUID taskId, UUID userId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: id=" + taskId));
        resolveProject(organizationId, task.getProjectId(), userId);
        UUID effectiveOrgId = task.getOrganizationId();
        if (effectiveOrgId != null) {
            return codeLinkRepository.findAllByOrganizationIdAndTaskId(effectiveOrgId, taskId);
        }

        return codeLinkRepository.findAllByTaskId(taskId);
    }

    public Project resolveProject(UUID organizationId, UUID projectId, UUID userId) {
        if (projectId == null) {
            throw new IllegalArgumentException("Project ID is required");
        }
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: id=" + projectId));

        if (project.getOrganizationId() != null) {
            if (organizationId != null && !organizationId.equals(project.getOrganizationId())) {
                throw new ResourceNotFoundException("Project not found: id=" + projectId + " in org=" + organizationId);
            }
            if (organizationId == null) {
                if (userId != null || com.forgeai.project.infrastructure.security.TenantContext.getUserId() != null || com.forgeai.project.infrastructure.security.TenantContext.getWorkspaceId() != null) {
                    throw new AccessDeniedException("Access denied: Project belongs to an organization");
                }
            }
        } else {
            if (organizationId != null) {
                throw new ResourceNotFoundException("Project not found: id=" + projectId + " in org=" + organizationId);
            }
            verifyPersonalProjectOwnership(project, userId != null ? userId : com.forgeai.project.infrastructure.security.TenantContext.getUserId());
        }
        return project;
    }

    private void verifyPersonalProjectOwnership(Project project, UUID userId) {
        if (project.getOrganizationId() == null) {
            WorkspaceJpaEntity ws = workspaceRepository.findById(project.getWorkspaceId())
                    .orElseThrow(() -> new ResourceNotFoundException("Workspace not found: id=" + project.getWorkspaceId()));
            if (userId == null || !userId.equals(ws.getOwnerUserId())) {
                throw new AccessDeniedException("Access denied to personal project: " + project.getId());
            }
        }
    }

    private void validateAssignee(UUID assigneeId) {
        UserDirectory user = userDirectoryRepository.findById(assigneeId)
                .orElseThrow(() -> new InvalidTaskAssignmentException("Assignee user does not exist in user directory: " + assigneeId));

        if (!user.isActive()) {
            throw new InvalidTaskAssignmentException("Assignee user is not in ACTIVE status: " + assigneeId + " (status: " + user.getStatus() + ")");
        }
    }
}
