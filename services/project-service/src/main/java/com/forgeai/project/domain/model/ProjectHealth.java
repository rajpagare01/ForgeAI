package com.forgeai.project.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectHealth {
    private ProjectHealthStatus status;
    private double healthScore; // 0.0 to 100.0
    private int totalRequirements;
    private int requirementsCompleted;
    private int requirementsAtRisk;
    private int requirementsWithoutImplementation;
    private int requirementsWithoutTests;
    private int openTasks;
    private int blockedTasks;
    private int activePrs;
    private int failingPrs;
    private int mergeReadinessBlockers;
    private int unresolvedSecurityFindings;
    private int unresolvedReliabilityFindings;
    private int activeRemediationActions;
    @Builder.Default
    private String evidenceStatus = "AVAILABLE"; // "AVAILABLE", "PARTIAL", "UNAVAILABLE"
    private List<String> reasons;
}
