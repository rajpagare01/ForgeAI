package com.forgeai.project.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RemoteRequirementTestTraceDto {
    private String requirementKey;
    private int totalTests;
    private int passedTests;
    private int failedTests;
    private List<RemoteTestCaseTraceSummary> tests;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RemoteTestCaseTraceSummary {
        private UUID testCaseId;
        private String testName;
        private String classname;
        private String latestStatus;
        private Long latestDurationMs;
        private UUID latestRunId;
        private String latestErrorMessage;
    }
}
