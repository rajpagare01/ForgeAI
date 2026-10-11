package com.forgeai.project.presentation.controller;

import com.forgeai.project.application.command.LinkCodeArtifactCommand;
import com.forgeai.project.application.command.UpdateTaskStatusCommand;
import com.forgeai.project.application.service.TaskApplicationService;
import com.forgeai.project.domain.model.CodeLink;
import com.forgeai.project.domain.model.Task;
import com.forgeai.project.infrastructure.security.TenantContext;
import com.forgeai.project.presentation.dto.CodeLinkResponse;
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
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskApplicationService taskApplicationService;

    @GetMapping("/{taskId}")
    public ResponseEntity<TaskResponse> getTask(@PathVariable UUID taskId) {
        UUID orgId = getRequiredOrgId();
        Task task = taskApplicationService.getTaskById(orgId, taskId);
        return ResponseEntity.ok(TaskResponse.from(task));
    }

    @PatchMapping("/{taskId}/status")
    public ResponseEntity<TaskResponse> updateStatus(
            @PathVariable UUID taskId,
            @Valid @RequestBody UpdateTaskStatusCommand command) {
        UUID orgId = getRequiredOrgId();
        Task task = taskApplicationService.updateTaskStatus(orgId, taskId, command);
        return ResponseEntity.ok(TaskResponse.from(task));
    }

    @PostMapping("/{taskId}/links")
    public ResponseEntity<CodeLinkResponse> linkCodeArtifact(
            @PathVariable UUID taskId,
            @Valid @RequestBody LinkCodeArtifactCommand command) {
        UUID orgId = getRequiredOrgId();
        CodeLink link = taskApplicationService.linkCodeArtifact(orgId, taskId, command);
        return ResponseEntity.status(HttpStatus.CREATED).body(CodeLinkResponse.from(link));
    }

    @GetMapping("/{taskId}/links")
    public ResponseEntity<List<CodeLinkResponse>> getCodeLinks(@PathVariable UUID taskId) {
        UUID orgId = getRequiredOrgId();
        List<CodeLinkResponse> list = taskApplicationService.getCodeLinksForTask(orgId, taskId).stream()
                .map(CodeLinkResponse::from)
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
}
