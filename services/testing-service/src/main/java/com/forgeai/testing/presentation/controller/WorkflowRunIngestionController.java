package com.forgeai.testing.presentation.controller;

import com.forgeai.testing.application.dto.WorkflowRunIngestionRequest;
import com.forgeai.testing.application.service.WorkflowRunIngestionService;
import com.forgeai.testing.domain.model.TestRun;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal ingestion endpoint for GitHub {@code workflow_run} events.
 * Invoked by Source Control (or an event relay) when a verified workflow run
 * is processed from the webhook pipeline.
 */
@Slf4j
@RestController
@RequestMapping
@RequiredArgsConstructor
public class WorkflowRunIngestionController {

    private final WorkflowRunIngestionService workflowRunIngestionService;

    @Value("${forgeai.security.enforce-internal-marker:true}")
    private boolean enforceInternalMarker;

    @PostMapping({"/api/v1/internal/workflow-runs", "/internal/workflow-runs"})
    public ResponseEntity<TestRun> ingestWorkflowRun(
            @RequestHeader(value = "X-ForgeAI-Internal", required = false) String internalHeader,
            @Valid @RequestBody WorkflowRunIngestionRequest request) {
        if (enforceInternalMarker && (internalHeader == null || !"attested".equalsIgnoreCase(internalHeader.trim()))) {
            log.warn("Rejected unattested request to internal workflow-runs ingestion endpoint");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        log.info("Received internal workflow_run ingestion request: orgId={}, repoId={}, workflowRunId={}, status={}, conclusion={}",
                request.getOrganizationId(), request.getRepositoryId(),
                request.getWorkflowRunId(), request.getStatus(), request.getConclusion());

        TestRun testRun = workflowRunIngestionService.ingestWorkflowRun(request);
        return ResponseEntity.ok(testRun);
    }
}
