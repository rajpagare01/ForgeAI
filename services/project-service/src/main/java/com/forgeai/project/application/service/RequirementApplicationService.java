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
        Project project;
        if (organizationId != null) {
            project = projectRepository.findByIdAndOrganizationId(projectId, organizationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found: id=" + projectId + " in org=" + organizationId));
        } else {
            project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found: id=" + projectId));
            verifyPersonalProjectOwnership(project, userId);
        }

        String effectiveIdentifier = command.identifier().trim().toUpperCase();
        if (requirementRepository.existsByProjectIdAndIdentifier(projectId, effectiveIdentifier)) {
            var existingList = organizationId != null
                    ? requirementRepository.findAllByOrganizationIdAndProjectId(organizationId, projectId)
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
                .organizationId(project.getOrganizationId())
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
        if (organizationId != null) {
            return requirementRepository.findByIdAndOrganizationId(requirementId, organizationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Requirement not found: id=" + requirementId + " in org=" + organizationId));
        }
        Requirement req = requirementRepository.findById(requirementId)
                .orElseThrow(() -> new ResourceNotFoundException("Requirement not found: id=" + requirementId));
        Project project = projectRepository.findById(req.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + req.getProjectId()));
        verifyPersonalProjectOwnership(project, userId);
        return req;
    }

    @Transactional(readOnly = true)
    public List<Requirement> listRequirements(UUID organizationId, UUID projectId) {
        return listRequirements(organizationId, projectId, null);
    }

    @Transactional(readOnly = true)
    public List<Requirement> listRequirements(UUID organizationId, UUID projectId, UUID userId) {
        if (organizationId != null) {
            projectRepository.findByIdAndOrganizationId(projectId, organizationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found: id=" + projectId + " in org=" + organizationId));
            return requirementRepository.findAllByOrganizationIdAndProjectId(organizationId, projectId);
        }

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: id=" + projectId));
        verifyPersonalProjectOwnership(project, userId);
        return requirementRepository.findAllByProjectId(projectId);
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
