package com.forgeai.testing.presentation.controller;

import com.forgeai.testing.application.command.RecordTestResultsBatchCommand;
import com.forgeai.testing.application.service.TestIngestionService;
import com.forgeai.testing.infrastructure.security.TenantContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/test-runs")
@RequiredArgsConstructor
public class TestIngestionController {

    private final TestIngestionService testIngestionService;

    @PostMapping("/{runId}/results/batch")
    public ResponseEntity<Map<String, Object>> ingestBatch(
            @PathVariable UUID runId,
            @Valid @RequestBody RecordTestResultsBatchCommand command) {
        UUID organizationId = TenantContext.getOrganizationId();
        int count = testIngestionService.ingestBatch(organizationId, runId, command.results());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "status", "INGESTED",
                "runId", runId,
                "ingestedCount", count
        ));
    }

    @PostMapping(value = "/{runId}/reports/junit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadJunitReportMultipart(
            @PathVariable UUID runId,
            @RequestParam("file") MultipartFile file) throws IOException {
        UUID organizationId = TenantContext.getOrganizationId();
        int count = testIngestionService.ingestJunitXml(organizationId, runId, file.getBytes());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "status", "INGESTED",
                "runId", runId,
                "ingestedCount", count
        ));
    }

    @PostMapping(value = "/{runId}/reports/junit", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<Map<String, Object>> uploadJunitReportXmlBody(
            @PathVariable UUID runId,
            @RequestBody byte[] xmlBytes) {
        UUID organizationId = TenantContext.getOrganizationId();
        int count = testIngestionService.ingestJunitXml(organizationId, runId, xmlBytes);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "status", "INGESTED",
                "runId", runId,
                "ingestedCount", count
        ));
    }
}
