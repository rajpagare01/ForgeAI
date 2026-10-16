package com.forgeai.project.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RemoteTestResultDto {
    private UUID id;
    private UUID runId;
    private UUID caseId;
    private String status;
    private Long durationMs;
    private String errorMessage;
    private String stackTrace;
}
