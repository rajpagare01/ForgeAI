package com.forgeai.project.presentation.controller;

import com.forgeai.project.application.service.TraceabilityAggregationService;
import com.forgeai.project.infrastructure.security.TenantContext;
import com.forgeai.project.presentation.dto.TraceabilitySpineChainDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/requirements")
@RequiredArgsConstructor
public class RequirementTraceabilityController {

    private final TraceabilityAggregationService traceabilityAggregationService;

    @GetMapping("/{requirementId}/traceability")
    public ResponseEntity<TraceabilitySpineChainDto> getRequirementTraceability(@PathVariable UUID requirementId) {
        UUID orgId = getRequiredOrgId();
        return ResponseEntity.ok(traceabilityAggregationService.getRequirementTraceability(orgId, requirementId));
    }

    private UUID getRequiredOrgId() {
        UUID orgId = TenantContext.getOrganizationId();
        if (orgId == null) {
            throw new IllegalArgumentException("Missing required X-Org-Id tenant context header");
        }
        return orgId;
    }
}
