package com.forgeai.testing.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestResult {
    private UUID id;
    private UUID organizationId;
    private UUID testRunId;
    private UUID testCaseId;
    private TestResultStatus status;
    private Long durationMs;
    private String errorMessage;
    private String stackTrace;
    private Instant createdAt;
}
