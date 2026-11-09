package com.forgeai.project.application.service;

import com.forgeai.project.application.command.CreateProjectCommand;
import com.forgeai.project.domain.event.ProjectCreatedEvent;
import com.forgeai.project.domain.exception.AccessDeniedException;
import com.forgeai.project.domain.exception.DuplicateResourceException;
import com.forgeai.project.domain.exception.ResourceNotFoundException;
import com.forgeai.project.domain.model.Project;
import com.forgeai.project.domain.model.ProjectStatus;
import com.forgeai.project.domain.repository.ProjectRepository;
import com.forgeai.project.infrastructure.outbox.OutboxService;
import com.forgeai.project.infrastructure.persistence.entity.WorkspaceJpaEntity;
import com.forgeai.project.infrastructure.persistence.repository.SpringDataWorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectApplicationService {

    private final ProjectRepository projectRepository;
    private final SpringDataWorkspaceRepository workspaceRepository;
    private final OutboxService outboxService;

    /**
     * Backward-compatible overload for organization-scoped project creation.
     */
    @Transactional
    public Project createProject(UUID organizationId, CreateProjectCommand command) {
        return createProject(command != null ? command.workspaceId() : null, organizationId, null, command);
    }

    /**
     * Creates a project under the specified or derived workspace (PERSONAL or ORGANIZATION).
     * Enforces strict owner-only access for personal workspaces and RBAC/membership for organizations.
     */
    @Transactional
    public Project createProject(UUID workspaceId, UUID organizationId, UUID callerUserId, CreateProjectCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("CreateProjectCommand cannot be null");
        }

        UUID targetWorkspaceId = command.workspaceId() != null ? command.workspaceId() : workspaceId;
        WorkspaceJpaEntity workspace = resolveWorkspace(targetWorkspaceId, organizationId, callerUserId);

        // Authorization and consistency check on resolved workspace
        UUID effectiveOrgId = null;
        if ("PERSONAL".equalsIgnoreCase(workspace.getType())) {
            if (organizationId != null) {
                log.warn("Personal workspace {} cannot be used with organization context {}", workspace.getId(), organizationId);
                throw new IllegalArgumentException("Personal workspace cannot be associated with an organization context");
            }
            if (callerUserId == null || !callerUserId.equals(workspace.getOwnerUserId())) {
                log.warn("Access denied: User {} is not the owner of personal workspace {}", callerUserId, workspace.getId());
                throw new AccessDeniedException("Access denied: You do not own this personal workspace");
            }
            effectiveOrgId = null;
        } else if ("ORGANIZATION".equalsIgnoreCase(workspace.getType())) {
            if (organizationId == null) {
                log.warn("Organization workspace {} requires organization context", workspace.getId());
                throw new IllegalArgumentException("Organization workspace requires organization context");
            }
            effectiveOrgId = workspace.getOrganizationId();
            if (!organizationId.equals(effectiveOrgId)) {
                log.warn("Access denied: Organization context mismatch {} vs workspace org {}", organizationId, effectiveOrgId);
                throw new IllegalArgumentException("Organization mismatch for workspace " + workspace.getId());
            }
        }

        String projectKey = command.key().toUpperCase();

        // Check uniqueness by workspace
        if (projectRepository.existsByWorkspaceIdAndKey(workspace.getId(), projectKey)) {
            throw new DuplicateResourceException("Project with key '" + projectKey + "' already exists in workspace " + workspace.getId());
        }

        // Also check organization key uniqueness if organization project
        if (effectiveOrgId != null && projectRepository.existsByOrganizationIdAndKey(effectiveOrgId, projectKey)) {
            throw new DuplicateResourceException("Project with key '" + projectKey + "' already exists in organization " + effectiveOrgId);
        }

        Instant now = Instant.now();
        Project project = Project.builder()
                .workspaceId(workspace.getId())
                .organizationId(effectiveOrgId)
                .name(command.name())
                .key(projectKey)
                .description(command.description())
                .status(ProjectStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();

        Project saved = projectRepository.save(project);

        // Transactional outbox event
        ProjectCreatedEvent event = ProjectCreatedEvent.builder()
                .projectId(saved.getId())
                .organizationId(effectiveOrgId != null ? effectiveOrgId : UUID.fromString("00000000-0000-0000-0000-000000000000"))
                .key(saved.getKey())
                .name(saved.getName())
                .timestamp(now)
                .build();
        outboxService.publish(event, "trace-proj-" + saved.getId());

        log.info("Created project: id={}, key={}, workspaceId={}, orgId={}",
                saved.getId(), saved.getKey(), workspace.getId(), effectiveOrgId);
        return saved;
    }

    /**
     * Backward-compatible overload for getting project by ID and organization.
     */
    @Transactional(readOnly = true)
    public Project getProjectById(UUID organizationId, UUID projectId) {
        return getProject(projectId, organizationId, null);
    }

    /**
     * Retrieves a project by ID and verifies access:
     * - PERSONAL workspace project: caller must be owner_user_id.
     * - ORGANIZATION workspace project: caller must belong to organization_id.
     */
    @Transactional(readOnly = true)
    public Project getProject(UUID projectId, UUID callerOrgId, UUID callerUserId) {
        if (projectId == null) {
            throw new IllegalArgumentException("Project ID is required");
        }

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: id=" + projectId));

        // Load project's workspace to verify authorization
        WorkspaceJpaEntity workspace = workspaceRepository.findById(project.getWorkspaceId())
                .orElse(null);

        if (workspace != null && "PERSONAL".equalsIgnoreCase(workspace.getType())) {
            if (callerUserId == null || !callerUserId.equals(workspace.getOwnerUserId())) {
                log.warn("Access denied: User {} does not own personal project {}", callerUserId, projectId);
                throw new AccessDeniedException("Access denied: You do not own this personal project");
            }
        } else {
            // Organization project
            if (callerOrgId != null && project.getOrganizationId() != null && !callerOrgId.equals(project.getOrganizationId())) {
                log.warn("Access denied: Org mismatch {} vs project org {}", callerOrgId, project.getOrganizationId());
                throw new ResourceNotFoundException("Project not found: id=" + projectId);
            }
            if (callerOrgId == null && project.getOrganizationId() != null) {
                // Requested without org context for an org project
                log.warn("Access denied: Project {} requires organization context", projectId);
                throw new AccessDeniedException("Access denied: Project belongs to an organization");
            }
        }

        return project;
    }

    /**
     * Backward-compatible overload for listing projects by organization.
     */
    @Transactional(readOnly = true)
    public List<Project> listProjects(UUID organizationId) {
        return listProjects(null, organizationId, null);
    }

    /**
     * Lists projects accessible in the requested workspace or tenant context.
     */
    @Transactional
    public List<Project> listProjects(UUID workspaceId, UUID organizationId, UUID callerUserId) {
        if (workspaceId != null) {
            Optional<WorkspaceJpaEntity> wsOpt = workspaceRepository.findById(workspaceId);
            if (wsOpt.isEmpty()) {
                if (organizationId != null) {
                    Optional<WorkspaceJpaEntity> orgWs = workspaceRepository.findByTypeAndOrganizationId("ORGANIZATION", organizationId);
                    if (orgWs.isPresent()) {
                        wsOpt = orgWs;
                    }
                } else if (callerUserId != null) {
                    Optional<WorkspaceJpaEntity> personalWs = workspaceRepository.findByTypeAndOwnerUserId("PERSONAL", callerUserId);
                    if (personalWs.isPresent()) {
                        wsOpt = personalWs;
                    }
                }
            }
            if (wsOpt.isEmpty() && callerUserId != null) {
                // Auto-provision workspace locally if provisioned in identity-service
                WorkspaceJpaEntity autoWs = WorkspaceJpaEntity.builder()
                        .id(workspaceId)
                        .type(organizationId != null ? "ORGANIZATION" : "PERSONAL")
                        .ownerUserId(callerUserId)
                        .organizationId(organizationId)
                        .name(organizationId != null ? "Organization Workspace" : "Personal Workspace")
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
                try {
                    wsOpt = Optional.of(workspaceRepository.save(autoWs));
                } catch (Exception ignored) {
                    wsOpt = workspaceRepository.findById(workspaceId);
                }
            }
            WorkspaceJpaEntity workspace = wsOpt
                    .orElseThrow(() -> new ResourceNotFoundException("Workspace not found: " + workspaceId));
            if ("PERSONAL".equalsIgnoreCase(workspace.getType())) {
                if (callerUserId == null || !callerUserId.equals(workspace.getOwnerUserId())) {
                    throw new AccessDeniedException("Access denied: You do not own this personal workspace");
                }
            } else if ("ORGANIZATION".equalsIgnoreCase(workspace.getType())) {
                if (organizationId != null && !organizationId.equals(workspace.getOrganizationId())) {
                    throw new AccessDeniedException("Access denied: Organization mismatch for workspace " + workspaceId);
                }
            }
            return projectRepository.findAllByWorkspaceId(workspace.getId());
        }

        if (organizationId != null) {
            return projectRepository.findAllByOrganizationId(organizationId);
        }

        if (callerUserId != null) {
            // List personal workspace projects
            Optional<WorkspaceJpaEntity> personalWs = workspaceRepository.findByTypeAndOwnerUserId("PERSONAL", callerUserId);
            if (personalWs.isPresent()) {
                return projectRepository.findAllByWorkspaceId(personalWs.get().getId());
            }
            return Collections.emptyList();
        }

        throw new IllegalArgumentException("Missing required X-Org-Id tenant context header");
    }

    private WorkspaceJpaEntity resolveWorkspace(UUID workspaceId, UUID organizationId, UUID callerUserId) {
        if (workspaceId != null) {
            Optional<WorkspaceJpaEntity> ws = workspaceRepository.findById(workspaceId);
            if (ws.isPresent()) {
                return ws.get();
            }
            // Check if organization or personal workspace already exists
            if (organizationId != null) {
                Optional<WorkspaceJpaEntity> orgWs = workspaceRepository.findByTypeAndOrganizationId("ORGANIZATION", organizationId);
                if (orgWs.isPresent()) {
                    return orgWs.get();
                }
                if (workspaceId.equals(organizationId)) {
                    return getOrCreateOrgWorkspace(organizationId, callerUserId);
                }
            } else if (callerUserId != null) {
                Optional<WorkspaceJpaEntity> personalWs = workspaceRepository.findByTypeAndOwnerUserId("PERSONAL", callerUserId);
                if (personalWs.isPresent()) {
                    return personalWs.get();
                }
            }
            if (callerUserId != null) {
                WorkspaceJpaEntity autoWs = WorkspaceJpaEntity.builder()
                        .id(workspaceId)
                        .type(organizationId != null ? "ORGANIZATION" : "PERSONAL")
                        .ownerUserId(callerUserId)
                        .organizationId(organizationId)
                        .name(organizationId != null ? "Organization Workspace" : "Personal Workspace")
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
                try {
                    return workspaceRepository.save(autoWs);
                } catch (Exception ignored) {
                    return workspaceRepository.findById(workspaceId)
                            .orElseThrow(() -> new ResourceNotFoundException("Workspace not found: " + workspaceId));
                }
            }
            throw new ResourceNotFoundException("Workspace not found: " + workspaceId);
        }

        if (organizationId != null) {
            return getOrCreateOrgWorkspace(organizationId, callerUserId);
        }

        if (callerUserId != null) {
            return getOrCreatePersonalWorkspace(callerUserId);
        }

        throw new IllegalArgumentException("Cannot resolve workspace: neither workspaceId, organizationId, nor callerUserId provided");
    }

    public WorkspaceJpaEntity getOrCreatePersonalWorkspace(UUID userId) {
        Optional<WorkspaceJpaEntity> existing = workspaceRepository.findByTypeAndOwnerUserId("PERSONAL", userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = Instant.now();
        WorkspaceJpaEntity ws = WorkspaceJpaEntity.builder()
                .type("PERSONAL")
                .ownerUserId(userId)
                .organizationId(null)
                .name("Personal Workspace")
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            return workspaceRepository.save(ws);
        } catch (DataIntegrityViolationException e) {
            return workspaceRepository.findByTypeAndOwnerUserId("PERSONAL", userId)
                    .orElseThrow(() -> e);
        }
    }

    public WorkspaceJpaEntity getOrCreateOrgWorkspace(UUID organizationId, UUID callerUserId) {
        Optional<WorkspaceJpaEntity> existing = workspaceRepository.findByTypeAndOrganizationId("ORGANIZATION", organizationId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = Instant.now();
        WorkspaceJpaEntity ws = WorkspaceJpaEntity.builder()
                .id(organizationId)
                .type("ORGANIZATION")
                .ownerUserId(callerUserId != null ? callerUserId : UUID.fromString("00000000-0000-0000-0000-000000000000"))
                .organizationId(organizationId)
                .name("Organization Workspace")
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            return workspaceRepository.save(ws);
        } catch (DataIntegrityViolationException e) {
            return workspaceRepository.findByTypeAndOrganizationId("ORGANIZATION", organizationId)
                    .orElseThrow(() -> e);
        }
    }
}
