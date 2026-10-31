package com.forgeai.project.presentation.controller;

import com.forgeai.project.application.command.CreateProjectCommand;
import com.forgeai.project.application.command.CreateRequirementCommand;
import com.forgeai.project.application.command.CreateTaskCommand;
import com.forgeai.project.application.service.ProjectApplicationService;
import com.forgeai.project.application.service.RequirementApplicationService;
import com.forgeai.project.application.service.TaskApplicationService;
import com.forgeai.project.domain.model.Project;
import com.forgeai.project.domain.model.ProjectRepositoryLink;
import com.forgeai.project.domain.model.Requirement;
import com.forgeai.project.domain.model.Task;
import com.forgeai.project.domain.model.TaskStatus;
import com.forgeai.project.domain.repository.ProjectRepositoryLinkRepository;
import com.forgeai.project.domain.repository.RequirementRepository;
import com.forgeai.project.domain.repository.TaskRepository;
import com.forgeai.project.infrastructure.security.TenantContext;
import com.forgeai.project.presentation.dto.ProjectResponse;
import com.forgeai.project.presentation.dto.RequirementResponse;
import com.forgeai.project.presentation.dto.TaskResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private ProjectApplicationService projectApplicationService;
    private RequirementApplicationService requirementApplicationService;
    private TaskApplicationService taskApplicationService;
    private ProjectRepositoryLinkRepository projectRepositoryLinkRepository;
    private RequirementRepository requirementRepository;
    private TaskRepository taskRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectController(ProjectApplicationService projectApplicationService,
                             RequirementApplicationService requirementApplicationService,
                             TaskApplicationService taskApplicationService,
                             ProjectRepositoryLinkRepository projectRepositoryLinkRepository,
                             RequirementRepository requirementRepository,
                             TaskRepository taskRepository) {
        this.projectApplicationService = projectApplicationService;
        this.requirementApplicationService = requirementApplicationService;
        this.taskApplicationService = taskApplicationService;
        this.projectRepositoryLinkRepository = projectRepositoryLinkRepository;
        this.requirementRepository = requirementRepository;
        this.taskRepository = taskRepository;
    }

    public ProjectController(ProjectApplicationService projectApplicationService,
                             RequirementApplicationService requirementApplicationService,
                             TaskApplicationService taskApplicationService) {
        this(projectApplicationService, requirementApplicationService, taskApplicationService, null, null, null);
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> createProject(@Valid @RequestBody CreateProjectCommand command) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        if (orgId == null && userId == null) {
            throw new IllegalArgumentException("Either X-Org-Id or authenticated user context must be provided");
        }
        UUID effectiveWsId = command.workspaceId() != null ? command.workspaceId() : TenantContext.getWorkspaceId();
        Project project = projectApplicationService.createProject(effectiveWsId, orgId, userId, command);
        return ResponseEntity.status(HttpStatus.CREATED).body(enrichProjectResponse(project, orgId));
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> getProject(@PathVariable UUID projectId) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        Project project = projectApplicationService.getProject(projectId, orgId, userId);
        return ResponseEntity.ok(enrichProjectResponse(project, orgId));
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> listProjects(@RequestParam(required = false) UUID workspaceId) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        UUID effectiveWsId = workspaceId != null ? workspaceId : TenantContext.getWorkspaceId();
        List<ProjectResponse> list = projectApplicationService.listProjects(effectiveWsId, orgId, userId).stream()
                .map(p -> enrichProjectResponse(p, orgId))
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
        List<Task> tasks = taskApplicationService.listTasks(orgId, projectId, userId, status);

        Map<UUID, String> reqKeyMap = new HashMap<>();
        if (requirementRepository != null) {
            try {
                List<Requirement> reqs = orgId != null
                        ? requirementRepository.findAllByOrganizationIdAndProjectId(orgId, projectId)
                        : requirementRepository.findAllByProjectId(projectId);
                if (reqs != null) {
                    for (Requirement r : reqs) {
                        reqKeyMap.put(r.getId(), r.getIdentifier());
                    }
                }
            } catch (Exception ignored) {}
        }

        List<TaskResponse> list = tasks.stream()
                .map(t -> {
                    String reqKey = t.getRequirementId() != null ? reqKeyMap.get(t.getRequirementId()) : null;
                    return TaskResponse.from(t, reqKey, null, null);
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    private ProjectResponse enrichProjectResponse(Project project, UUID orgId) {
        if (project == null) return null;
        String repoName = null;
        if (projectRepositoryLinkRepository != null) {
            try {
                List<ProjectRepositoryLink> links = orgId != null
                        ? projectRepositoryLinkRepository.findByProjectId(orgId, project.getId())
                        : projectRepositoryLinkRepository.findByProjectId(project.getId());
                if (links != null && !links.isEmpty()) {
                    repoName = links.get(0).getRepositoryName();
                }
            } catch (Exception ignored) {}
        }

        int reqCount = 0;
        if (requirementRepository != null) {
            try {
                List<Requirement> reqs = orgId != null
                        ? requirementRepository.findAllByOrganizationIdAndProjectId(orgId, project.getId())
                        : requirementRepository.findAllByProjectId(project.getId());
                if (reqs != null) reqCount = reqs.size();
            } catch (Exception ignored) {}
        }

        int tCount = 0;
        if (taskRepository != null) {
            try {
                List<Task> tasks = orgId != null
                        ? taskRepository.findAllByOrganizationIdAndProjectId(orgId, project.getId())
                        : taskRepository.findAllByProjectId(project.getId());
                if (tasks != null) tCount = tasks.size();
            } catch (Exception ignored) {}
        }

        Double health = 95.0;
        return ProjectResponse.from(project, repoName, reqCount, tCount, health);
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
