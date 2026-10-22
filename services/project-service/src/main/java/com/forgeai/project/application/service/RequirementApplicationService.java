package com.forgeai.project.application.service;

import com.forgeai.project.application.command.CreateRequirementCommand;
import com.forgeai.project.domain.exception.DuplicateResourceException;
import com.forgeai.project.domain.exception.ResourceNotFoundException;
import com.forgeai.project.domain.model.Project;
import com.forgeai.project.domain.model.Requirement;
import com.forgeai.project.domain.model.RequirementStatus;
import com.forgeai.project.domain.repository.ProjectRepository;
import com.forgeai.project.domain.repository.RequirementRepository;
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

    @Transactional
    public Requirement createRequirement(UUID organizationId, UUID projectId, CreateRequirementCommand command) {
        Project project;
        if (organizationId != null) {
            project = projectRepository.findByIdAndOrganizationId(projectId, organizationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found: id=" + projectId + " in org=" + organizationId));
        } else {
            project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found: id=" + projectId));
        }

        if (requirementRepository.existsByProjectIdAndIdentifier(projectId, command.identifier())) {
            throw new DuplicateResourceException("Requirement with identifier '" + command.identifier() + "' already exists in project " + projectId);
        }

        Instant now = Instant.now();
        Requirement requirement = Requirement.builder()
                .projectId(project.getId())
                .organizationId(project.getOrganizationId())
                .identifier(command.identifier().toUpperCase())
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
        if (organizationId != null) {
            return requirementRepository.findByIdAndOrganizationId(requirementId, organizationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Requirement not found: id=" + requirementId + " in org=" + organizationId));
        }
        return requirementRepository.findById(requirementId)
                .orElseThrow(() -> new ResourceNotFoundException("Requirement not found: id=" + requirementId));
    }

    @Transactional(readOnly = true)
    public List<Requirement> listRequirements(UUID organizationId, UUID projectId) {
        if (organizationId != null) {
            projectRepository.findByIdAndOrganizationId(projectId, organizationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found: id=" + projectId + " in org=" + organizationId));
            return requirementRepository.findAllByOrganizationIdAndProjectId(organizationId, projectId);
        }

        projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: id=" + projectId));
        return requirementRepository.findAllByProjectId(projectId);
    }
}
