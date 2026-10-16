package com.forgeai.project.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class RemoteTestRunDto {
    private UUID id;
    private UUID organizationId;
    private UUID repositoryId;
    private UUID suiteId;
    private String commitSha;
    private String branch;
    private String environment;
    private String status;
    private Integer totalTests;
    private Integer passedTests;
    private Integer failedTests;
    private Integer skippedTests;
    private Long durationMs;
    private Instant startedAt;
    private Instant completedAt;
}
