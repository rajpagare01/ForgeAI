package com.forgeai.testing.presentation.controller;

import com.forgeai.testing.application.dto.RequirementTraceabilityResponse;
import com.forgeai.testing.application.service.RequirementTraceabilityService;
import com.forgeai.testing.infrastructure.security.TenantContext;
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
@RequestMapping("/api/v1/traceability/requirements")
@RequiredArgsConstructor
public class RequirementTestTraceController {

    private final RequirementTraceabilityService requirementTraceabilityService;

    @GetMapping("/{reqKey}/tests")
    public ResponseEntity<RequirementTraceabilityResponse> getTestsForRequirement(@PathVariable String reqKey) {
        UUID organizationId = TenantContext.getOrganizationId();
        RequirementTraceabilityResponse response = requirementTraceabilityService.getTraceabilityForRequirement(organizationId, reqKey);
        return ResponseEntity.ok(response);
    }
}
