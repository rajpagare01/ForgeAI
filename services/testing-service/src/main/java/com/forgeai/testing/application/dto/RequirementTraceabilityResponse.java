package com.forgeai.testing.application.dto;

import com.forgeai.testing.domain.model.TestResultStatus;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.UUID;

@Value
@Builder
public class RequirementTraceabilityResponse {
    String requirementKey;
    int totalTests;
    int passedTests;
    int failedTests;
    List<TestCaseTraceSummary> tests;

    @Value
    @Builder
    public static class TestCaseTraceSummary {
        UUID testCaseId;
        String testName;
        String classname;
        TestResultStatus latestStatus;
        Long latestDurationMs;
        UUID latestRunId;
        String latestErrorMessage;
    }
}
