package com.forgeai.project.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequirementHealth {
    private UUID requirementId;
    private String identifier;
    private String title;
    private RequirementHealthStatus status;
    private String implementationStatus; // NOT_STARTED, IN_PROGRESS, IMPLEMENTED
    private String testStatus;           // NO_TESTS, TESTS_FAILING, TESTS_PASSED
    private String riskStatus;           // NO_RISK, LOW, MEDIUM, HIGH, CRITICAL
    private String traceabilityStatus;   // COMPLETE, PARTIAL, ORPHAN
    private double completenessScore;    // 0.0 to 100.0
    private int linkedTasksCount;
    private int linkedPrsCount;
    private int testsCount;
    private Double activeRiskScore;
    private int remediationCount;
}
