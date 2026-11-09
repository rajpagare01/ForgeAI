package com.forgeai.project.application.service;

import com.forgeai.project.application.command.CreateRequirementCommand;
import com.forgeai.project.domain.exception.AccessDeniedException;
import com.forgeai.project.domain.exception.DuplicateResourceException;
import com.forgeai.project.domain.exception.ResourceNotFoundException;
import com.forgeai.project.domain.model.Project;
import com.forgeai.project.domain.model.Requirement;
import com.forgeai.project.domain.model.RequirementStatus;
import com.forgeai.project.domain.repository.ProjectRepository;
import com.forgeai.project.domain.repository.RequirementRepository;
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
public class RequirementApplicationService {

    private final ProjectRepository projectRepository;
    private final RequirementRepository requirementRepository;
    private final SpringDataWorkspaceRepository workspaceRepository;

    @Transactional
    public Requirement createRequirement(UUID organizationId, UUID projectId, CreateRequirementCommand command) {
        return createRequirement(organizationId, projectId, null, command);
    }

    @Transactional
    public Requirement createRequirement(UUID organizationId, UUID projectId, UUID userId, CreateRequirementCommand command) {
        Project project = resolveProject(organizationId, projectId, userId);
        UUID effectiveOrgId = project.getOrganizationId();

        String effectiveIdentifier = command.identifier().trim().toUpperCase();
        if (requirementRepository.existsByProjectIdAndIdentifier(projectId, effectiveIdentifier)) {
            var existingList = effectiveOrgId != null
                    ? requirementRepository.findAllByOrganizationIdAndProjectId(effectiveOrgId, projectId)
                    : requirementRepository.findAllByProjectId(projectId);
            for (Requirement r : existingList) {
                if (effectiveIdentifier.equalsIgnoreCase(r.getIdentifier())) {
                    if (r.getTitle() != null && r.getTitle().equalsIgnoreCase(command.title())) {
                        log.info("Requirement {} already exists with matching title; returning existing requirement idempotently", effectiveIdentifier);
                        return r;
                    }
                }
            }
            int suffix = 1;
            while (requirementRepository.existsByProjectIdAndIdentifier(projectId, effectiveIdentifier + "-" + suffix)) {
                suffix++;
            }
            effectiveIdentifier = effectiveIdentifier + "-" + suffix;
        }

        Instant now = Instant.now();
        Requirement requirement = Requirement.builder()
                .projectId(project.getId())
                .workspaceId(project.getWorkspaceId())
                .organizationId(effectiveOrgId)
                .identifier(effectiveIdentifier)
                .title(command.title())
                .description(command.description())
                .status(RequirementStatus.DRAFT)
                .version(1)
                .createdAt(now)
                .updatedAt(now)
                .build();

        Requirement saved = requirementRepository.save(requirement);
        log.info("Created requirement: id={}, identifier={}, projectId={}", saved.getId(), saved.getIdentifier(), projectId);
        return saved;
    }

    @Transactional(readOnly = true)
    public Requirement getRequirementById(UUID organizationId, UUID requirementId) {
        return getRequirementById(organizationId, requirementId, null);
    }

    @Transactional(readOnly = true)
    public Requirement getRequirementById(UUID organizationId, UUID requirementId, UUID userId) {
        Requirement req = requirementRepository.findById(requirementId)
                .orElseThrow(() -> new ResourceNotFoundException("Requirement not found: id=" + requirementId));
        resolveProject(organizationId, req.getProjectId(), userId);
        return req;
    }

    @Transactional(readOnly = true)
    public List<Requirement> listRequirements(UUID organizationId, UUID projectId) {
        return listRequirements(organizationId, projectId, null);
    }

    @Transactional(readOnly = true)
    public List<Requirement> listRequirements(UUID organizationId, UUID projectId, UUID userId) {
        Project project = resolveProject(organizationId, projectId, userId);
        UUID effectiveOrgId = project.getOrganizationId();
        if (effectiveOrgId != null) {
            return requirementRepository.findAllByOrganizationIdAndProjectId(effectiveOrgId, projectId);
        }
        return requirementRepository.findAllByProjectId(projectId);
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
}
