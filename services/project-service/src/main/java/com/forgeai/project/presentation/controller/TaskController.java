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
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        Task task = taskApplicationService.getTaskById(orgId, taskId, userId);
        return ResponseEntity.ok(TaskResponse.from(task));
    }

    @PatchMapping("/{taskId}/status")
    public ResponseEntity<TaskResponse> updateStatus(
            @PathVariable UUID taskId,
            @Valid @RequestBody UpdateTaskStatusCommand command) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        Task task = taskApplicationService.updateTaskStatus(orgId, taskId, userId, command);
        return ResponseEntity.ok(TaskResponse.from(task));
    }

    @PostMapping("/{taskId}/links")
    public ResponseEntity<CodeLinkResponse> linkCodeArtifact(
            @PathVariable UUID taskId,
            @Valid @RequestBody LinkCodeArtifactCommand command) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        CodeLink link = taskApplicationService.linkCodeArtifact(orgId, taskId, userId, command);
        return ResponseEntity.status(HttpStatus.CREATED).body(CodeLinkResponse.from(link));
    }

    @GetMapping("/{taskId}/links")
    public ResponseEntity<List<CodeLinkResponse>> getCodeLinks(@PathVariable UUID taskId) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        List<CodeLinkResponse> list = taskApplicationService.getCodeLinksForTask(orgId, taskId, userId).stream()
                .map(CodeLinkResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }
}
