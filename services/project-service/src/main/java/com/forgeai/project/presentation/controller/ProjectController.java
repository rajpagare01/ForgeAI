package com.forgeai.project.presentation.controller;

import com.forgeai.project.application.command.CreateProjectCommand;
import com.forgeai.project.application.command.CreateRequirementCommand;
import com.forgeai.project.application.command.CreateTaskCommand;
import com.forgeai.project.application.service.ProjectApplicationService;
import com.forgeai.project.application.service.RequirementApplicationService;
import com.forgeai.project.application.service.TaskApplicationService;
import com.forgeai.project.domain.model.Project;
import com.forgeai.project.domain.model.Requirement;
import com.forgeai.project.domain.model.Task;
import com.forgeai.project.domain.model.TaskStatus;
import com.forgeai.project.infrastructure.security.TenantContext;
import com.forgeai.project.presentation.dto.ProjectResponse;
import com.forgeai.project.presentation.dto.RequirementResponse;
import com.forgeai.project.presentation.dto.TaskResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectApplicationService projectApplicationService;
    private final RequirementApplicationService requirementApplicationService;
    private final TaskApplicationService taskApplicationService;

    @PostMapping
    public ResponseEntity<ProjectResponse> createProject(@Valid @RequestBody CreateProjectCommand command) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        if (orgId == null && userId == null) {
            throw new IllegalArgumentException("Either X-Org-Id or authenticated user context must be provided");
        }
        Project project = projectApplicationService.createProject(command.workspaceId(), orgId, userId, command);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectResponse.from(project));
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> getProject(@PathVariable UUID projectId) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        Project project = projectApplicationService.getProject(projectId, orgId, userId);
        return ResponseEntity.ok(ProjectResponse.from(project));
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> listProjects(@RequestParam(required = false) UUID workspaceId) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        List<ProjectResponse> list = projectApplicationService.listProjects(workspaceId, orgId, userId).stream()
                .map(ProjectResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    // Requirements sub-resource
    @PostMapping("/{projectId}/requirements")
    public ResponseEntity<RequirementResponse> createRequirement(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateRequirementCommand command) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        Requirement requirement = requirementApplicationService.createRequirement(orgId, projectId, userId, command);
        return ResponseEntity.status(HttpStatus.CREATED).body(RequirementResponse.from(requirement));
    }

    @GetMapping("/{projectId}/requirements")
    public ResponseEntity<List<RequirementResponse>> listRequirements(@PathVariable UUID projectId) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        List<RequirementResponse> list = requirementApplicationService.listRequirements(orgId, projectId, userId).stream()
                .map(RequirementResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    // Tasks sub-resource under project
    @PostMapping("/{projectId}/tasks")
    public ResponseEntity<TaskResponse> createTask(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateTaskCommand command) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID reporterId = getRequiredUserId();
        Task task = taskApplicationService.createTask(orgId, projectId, reporterId, command);
        return ResponseEntity.status(HttpStatus.CREATED).body(TaskResponse.from(task));
    }

    @GetMapping("/{projectId}/tasks")
    public ResponseEntity<List<TaskResponse>> listTasks(
            @PathVariable UUID projectId,
            @RequestParam(required = false) TaskStatus status) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        List<TaskResponse> list = taskApplicationService.listTasks(orgId, projectId, userId, status).stream()
                .map(TaskResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    private UUID getRequiredOrgId() {
        UUID orgId = TenantContext.getOrganizationId();
        if (orgId == null) {
            throw new IllegalArgumentException("Missing required X-Org-Id tenant context header");
        }
        return orgId;
    }

    private UUID getRequiredUserId() {
        UUID userId = TenantContext.getUserId();
        if (userId == null) {
            throw new IllegalArgumentException("Missing required X-User-Id authenticated context header");
        }
        return userId;
    }
}
