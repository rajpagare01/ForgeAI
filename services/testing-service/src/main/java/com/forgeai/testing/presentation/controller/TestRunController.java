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
        TestRun run = testRunApplicationService.createTestRun(organizationId, command);
        return ResponseEntity.status(HttpStatus.CREATED).body(run);
    }

    @GetMapping("/{runId}")
    public ResponseEntity<TestRun> getTestRun(@PathVariable UUID runId) {
        UUID organizationId = TenantContext.getOrganizationId();
        TestRun run = testRunApplicationService.getTestRun(organizationId, runId);
        return ResponseEntity.ok(run);
    }

    @PostMapping("/{runId}/complete")
    public ResponseEntity<TestRun> completeTestRun(@PathVariable UUID runId) {
        UUID organizationId = TenantContext.getOrganizationId();
        TestRun run = testRunApplicationService.completeTestRun(organizationId, runId);
        return ResponseEntity.ok(run);
    }
}
