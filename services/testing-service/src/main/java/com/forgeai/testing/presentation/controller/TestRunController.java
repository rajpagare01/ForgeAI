package com.forgeai.testing.presentation.controller;

import com.forgeai.testing.application.command.CreateTestRunCommand;
import com.forgeai.testing.application.service.TestRunApplicationService;
import com.forgeai.testing.domain.model.TestRun;
import com.forgeai.testing.infrastructure.security.TenantContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/test-runs")
@RequiredArgsConstructor
public class TestRunController {

    private final TestRunApplicationService testRunApplicationService;

    @PostMapping
    public ResponseEntity<TestRun> createTestRun(@Valid @RequestBody CreateTestRunCommand command) {
        UUID organizationId = TenantContext.getOrganizationId();
        UUID workspaceId = TenantContext.getWorkspaceId();
        String scope = TenantContext.getWorkspaceScope();
        TestRun run = testRunApplicationService.createTestRun(workspaceId, organizationId, scope, command);
        return ResponseEntity.status(HttpStatus.CREATED).body(run);
    }

    @GetMapping
    public ResponseEntity<java.util.List<TestRun>> listTestRuns(
            @RequestParam(required = false) UUID repositoryId,
            @RequestParam(required = false) String commitSha) {
        UUID organizationId = TenantContext.getOrganizationId();
        UUID workspaceId = TenantContext.getWorkspaceId();
        java.util.List<TestRun> runs = testRunApplicationService.listTestRuns(workspaceId, organizationId, repositoryId, commitSha);
        return ResponseEntity.ok(runs);
    }

    @GetMapping("/{runId}")
    public ResponseEntity<TestRun> getTestRun(@PathVariable UUID runId) {
        UUID organizationId = TenantContext.getOrganizationId();
        UUID workspaceId = TenantContext.getWorkspaceId();
        TestRun run = testRunApplicationService.getTestRun(workspaceId, organizationId, runId);
        return ResponseEntity.ok(run);
    }

    @GetMapping("/{runId}/results")
    public ResponseEntity<java.util.List<com.forgeai.testing.domain.model.TestResult>> getTestResults(@PathVariable UUID runId) {
        UUID organizationId = TenantContext.getOrganizationId();
        UUID workspaceId = TenantContext.getWorkspaceId();
        java.util.List<com.forgeai.testing.domain.model.TestResult> results = testRunApplicationService.getTestResults(workspaceId, organizationId, runId);
        return ResponseEntity.ok(results);
    }

    @PostMapping("/{runId}/complete")
    public ResponseEntity<TestRun> completeTestRun(@PathVariable UUID runId) {
        UUID organizationId = TenantContext.getOrganizationId();
        UUID workspaceId = TenantContext.getWorkspaceId();
        TestRun run = testRunApplicationService.completeTestRun(workspaceId, organizationId, runId);
        return ResponseEntity.ok(run);
    }
}
