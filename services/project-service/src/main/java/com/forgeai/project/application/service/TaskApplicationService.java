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

    @Transactional
    public Task createTask(UUID organizationId, UUID projectId, UUID reporterId, CreateTaskCommand command) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must be provided via tenant context");
        }
        if (reporterId == null) {
            throw new IllegalArgumentException("Reporter ID must be provided via tenant context");
        }

        // 1. Validate project belongs to organization
        Project project = projectRepository.findByIdAndOrganizationId(projectId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: id=" + projectId + " in org=" + organizationId));

        // 2. Validate requirement if supplied
        if (command.requirementId() != null) {
            requirementRepository.findByIdAndOrganizationId(command.requirementId(), organizationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Requirement not found: id=" + command.requirementId() + " in org=" + organizationId));
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
                .organizationId(organizationId)
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
                .organizationId(saved.getOrganizationId())
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
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must be provided via tenant context");
        }

        Task task = taskRepository.findByIdAndOrganizationId(taskId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: id=" + taskId + " in org=" + organizationId));

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
                .organizationId(updated.getOrganizationId())
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
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must be provided via tenant context");
        }

        Task task = taskRepository.findByIdAndOrganizationId(taskId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: id=" + taskId + " in org=" + organizationId));

        if (codeLinkRepository.existsByTaskIdAndLinkTypeAndExternalRef(taskId, command.linkType(), command.externalRef())) {
            throw new DuplicateResourceException("Code link for task " + taskId + " with ref " + command.externalRef() + " already exists");
        }

        Instant now = Instant.now();
        CodeLink codeLink = CodeLink.builder()
                .organizationId(organizationId)
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
                .organizationId(organizationId)
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
        return taskRepository.findByIdAndOrganizationId(taskId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: id=" + taskId + " in org=" + organizationId));
    }

    @Transactional(readOnly = true)
    public List<Task> listTasks(UUID organizationId, UUID projectId, TaskStatus status) {
        projectRepository.findByIdAndOrganizationId(projectId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: id=" + projectId + " in org=" + organizationId));

        if (status != null) {
            return taskRepository.findAllByOrganizationIdAndProjectIdAndStatus(organizationId, projectId, status);
        }
        return taskRepository.findAllByOrganizationIdAndProjectId(organizationId, projectId);
    }

    @Transactional(readOnly = true)
    public List<CodeLink> getCodeLinksForTask(UUID organizationId, UUID taskId) {
        taskRepository.findByIdAndOrganizationId(taskId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: id=" + taskId + " in org=" + organizationId));

        return codeLinkRepository.findAllByOrganizationIdAndTaskId(organizationId, taskId);
    }

    private void validateAssignee(UUID assigneeId) {
        UserDirectory user = userDirectoryRepository.findById(assigneeId)
                .orElseThrow(() -> new InvalidTaskAssignmentException("Assignee user does not exist in user directory: " + assigneeId));

        if (!user.isActive()) {
            throw new InvalidTaskAssignmentException("Assignee user is not in ACTIVE status: " + assigneeId + " (status: " + user.getStatus() + ")");
        }
    }
}
