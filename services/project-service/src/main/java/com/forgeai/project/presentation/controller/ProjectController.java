package com.forgeai.project.presentation.controller;

import com.forgeai.project.application.command.CreateProjectCommand;
import com.forgeai.project.application.command.CreateRequirementCommand;
import com.forgeai.project.application.command.CreateTaskCommand;
import com.forgeai.project.application.service.ProjectApplicationService;
import com.forgeai.project.application.service.RequirementApplicationService;
import com.forgeai.project.application.service.TaskApplicationService;
import com.forgeai.project.domain.model.CodeLink;
import com.forgeai.project.domain.model.CodeLinkType;
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
    private com.forgeai.project.domain.repository.CodeLinkRepository codeLinkRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectController(ProjectApplicationService projectApplicationService,
                             RequirementApplicationService requirementApplicationService,
                             TaskApplicationService taskApplicationService,
                             ProjectRepositoryLinkRepository projectRepositoryLinkRepository,
                             RequirementRepository requirementRepository,
                             TaskRepository taskRepository,
                             com.forgeai.project.domain.repository.CodeLinkRepository codeLinkRepository) {
        this.projectApplicationService = projectApplicationService;
        this.requirementApplicationService = requirementApplicationService;
        this.taskApplicationService = taskApplicationService;
        this.projectRepositoryLinkRepository = projectRepositoryLinkRepository;
        this.requirementRepository = requirementRepository;
        this.taskRepository = taskRepository;
        this.codeLinkRepository = codeLinkRepository;
    }

    public ProjectController(ProjectApplicationService projectApplicationService,
                             RequirementApplicationService requirementApplicationService,
                             TaskApplicationService taskApplicationService) {
        this(projectApplicationService, requirementApplicationService, taskApplicationService, null, null, null, null);
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
        UUID reporterId = TenantContext.getUserId();
        if (reporterId == null) {
            reporterId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        }
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
            } catch (Exception ex) {
                log.debug("Optional lookup skipped: {}", ex.getMessage());
            }
        }

        List<TaskResponse> list = tasks.stream()
                .map(t -> {
                    String reqKey = t.getRequirementId() != null ? reqKeyMap.get(t.getRequirementId()) : null;
                    String branchName = null;
                    Integer linkedPrNumber = null;

                    if (codeLinkRepository != null) {
                        try {
                            List<CodeLink> links = orgId != null
                                    ? codeLinkRepository.findAllByOrganizationIdAndTaskId(orgId, t.getId())
                                    : codeLinkRepository.findAllByTaskId(t.getId());
                            if (links != null) {
                                for (CodeLink link : links) {
                                    if (link.getLinkType() == CodeLinkType.BRANCH && link.getExternalRef() != null) {
                                        branchName = link.getExternalRef();
                                    } else if (link.getLinkType() == CodeLinkType.PULL_REQUEST && link.getExternalRef() != null) {
                                        String ref = link.getExternalRef();
                                        String digits = ref.replaceAll("[^0-9]", "");
                                        if (!digits.isBlank()) {
                                            try {
                                                linkedPrNumber = Integer.parseInt(digits);
                                            } catch (NumberFormatException ignored) {}
                                        }
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            log.debug("Optional code link lookup skipped for task {}: {}", t.getId(), ex.getMessage());
                        }
                    }

                    return TaskResponse.from(t, reqKey, linkedPrNumber, branchName);
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    public record GitActivityItem(
            String sha,
            String message,
            String branch,
            Integer prNumber,
            @jakarta.validation.constraints.NotNull(message = "repositoryId is required")
            UUID repositoryId
    ) {}

    public record SyncGitActivityRequest(
            List<@Valid GitActivityItem> activities
    ) {}

    @PostMapping("/{projectId}/sync-git-activity")
    public ResponseEntity<Map<String, Object>> syncGitActivity(
            @PathVariable UUID projectId,
            @RequestBody(required = false) SyncGitActivityRequest request) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        Project project = projectApplicationService.getProject(projectId, orgId, userId);
        if (project == null) {
            return ResponseEntity.notFound().build();
        }

        List<Task> tasks = taskApplicationService.listTasks(orgId, projectId, userId, null);
        int linkedCount = 0;
        int statusProgressedCount = 0;

        java.util.regex.Pattern fixPattern = java.util.regex.Pattern.compile(
                "(?i)\\b(fix|fixes|fixed|close|closes|closed|complete|completed|done)\\b");

        List<GitActivityItem> items = (request != null && request.activities() != null)
                ? request.activities()
                : List.of();

        for (Task task : tasks) {
            String key = task.getTaskKey();
            if (key == null) continue;

            for (GitActivityItem act : items) {
                boolean matched = false;
                if (act.message() != null && act.message().toUpperCase().contains(key.toUpperCase())) {
                    matched = true;
                } else if (act.branch() != null && act.branch().toUpperCase().contains(key.toUpperCase())) {
                    matched = true;
                }

                if (matched) {
                    if (act.sha() != null && !act.sha().isBlank() && codeLinkRepository != null) {
                        if (!codeLinkRepository.existsByTaskIdAndLinkTypeAndExternalRef(task.getId(), com.forgeai.project.domain.model.CodeLinkType.COMMIT, act.sha())) {
                            codeLinkRepository.save(com.forgeai.project.domain.model.CodeLink.builder()
                                    .organizationId(task.getOrganizationId())
                                    .workspaceId(task.getWorkspaceId() != null ? task.getWorkspaceId() : task.getOrganizationId())
                                    .taskId(task.getId())
                                    .linkType(com.forgeai.project.domain.model.CodeLinkType.COMMIT)
                                    .externalRef(act.sha())
                                    .repositoryId(act.repositoryId())
                                    .createdBy("GIT_SYNC_MANUAL")
                                    .createdAt(java.time.Instant.now())
                                    .build());
                            linkedCount++;
                        }
                    }
                    if (act.prNumber() != null && codeLinkRepository != null) {
                        String prRef = "PR-#" + act.prNumber();
                        if (!codeLinkRepository.existsByTaskIdAndLinkTypeAndExternalRef(task.getId(), com.forgeai.project.domain.model.CodeLinkType.PULL_REQUEST, prRef)) {
                            codeLinkRepository.save(com.forgeai.project.domain.model.CodeLink.builder()
                                    .organizationId(task.getOrganizationId())
                                    .workspaceId(task.getWorkspaceId() != null ? task.getWorkspaceId() : task.getOrganizationId())
                                    .taskId(task.getId())
                                    .linkType(com.forgeai.project.domain.model.CodeLinkType.PULL_REQUEST)
                                    .externalRef(prRef)
                                    .repositoryId(act.repositoryId())
                                    .createdBy("GIT_SYNC_MANUAL")
                                    .createdAt(java.time.Instant.now())
                                    .build());
                            linkedCount++;
                        }
                    }

                    boolean hasFix = act.message() != null && fixPattern.matcher(act.message()).find();
                    boolean changed = false;
                    if (hasFix && task.getStatus() != TaskStatus.DONE) {
                        task.setStatus(TaskStatus.DONE);
                        changed = true;
                    } else if (act.prNumber() != null && (task.getStatus() == TaskStatus.TODO || task.getStatus() == TaskStatus.IN_PROGRESS)) {
                        task.setStatus(TaskStatus.IN_REVIEW);
                        changed = true;
                    } else if (task.getStatus() == TaskStatus.TODO) {
                        task.setStatus(TaskStatus.IN_PROGRESS);
                        changed = true;
                    }
                    if (changed && taskRepository != null) {
                        task.setUpdatedAt(java.time.Instant.now());
                        taskRepository.save(task);
                        statusProgressedCount++;
                    }
                }
            }
        }

        return ResponseEntity.ok(Map.of(
                "projectId", projectId,
                "linkedCount", linkedCount,
                "statusProgressedCount", statusProgressedCount,
                "status", "SYNCED"
        ));
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
            } catch (Exception ex) {
                log.debug("Optional lookup skipped: {}", ex.getMessage());
            }
        }

        List<Requirement> projectReqs = List.of();
        if (requirementRepository != null) {
            try {
                List<Requirement> fetchedReqs = orgId != null
                        ? requirementRepository.findAllByOrganizationIdAndProjectId(orgId, project.getId())
                        : requirementRepository.findAllByProjectId(project.getId());
                if (fetchedReqs != null) projectReqs = fetchedReqs;
            } catch (Exception ex) {
                log.debug("Optional lookup skipped: {}", ex.getMessage());
            }
        }
        int reqCount = projectReqs.size();

        List<Task> projectTasks = List.of();
        if (taskRepository != null) {
            try {
                List<Task> fetchedTasks = orgId != null
                        ? taskRepository.findAllByOrganizationIdAndProjectId(orgId, project.getId())
                        : taskRepository.findAllByProjectId(project.getId());
                if (fetchedTasks != null) projectTasks = fetchedTasks;
            } catch (Exception ex) {
                log.debug("Optional lookup skipped: {}", ex.getMessage());
            }
        }
        int tCount = projectTasks.size();

        double health;
        if (reqCount == 0 && tCount == 0) {
            health = (repoName != null && !repoName.isBlank()) ? 80.0 : 60.0;
        } else {
            double taskScore = 1.0;
            if (tCount > 0) {
                double weightedSum = 0.0;
                for (Task t : projectTasks) {
                    if (t.getStatus() == TaskStatus.DONE) weightedSum += 1.0;
                    else if (t.getStatus() == TaskStatus.IN_REVIEW) weightedSum += 0.8;
                    else if (t.getStatus() == TaskStatus.IN_PROGRESS) weightedSum += 0.5;
                    else if (t.getStatus() == TaskStatus.TODO) weightedSum += 0.25;
                    else weightedSum += 0.0; // BLOCKED
                }
                taskScore = weightedSum / tCount;
            }
            double reqCoverage = 1.0;
            if (reqCount > 0 && tCount > 0) {
                java.util.Set<UUID> coveredReqIds = projectTasks.stream()
                        .map(Task::getRequirementId)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet());
                reqCoverage = (double) coveredReqIds.size() / reqCount;
            } else if (reqCount > 0) {
                reqCoverage = 0.3;
            }
            double repoBonus = (repoName != null && !repoName.isBlank()) ? 20.0 : 5.0;
            health = Math.min(100.0, Math.round((45.0 * taskScore + 35.0 * reqCoverage + repoBonus) * 10.0) / 10.0);
        }
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
