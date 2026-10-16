package com.forgeai.project.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RemoteEngineeringActionDto {
    private UUID id;
    private String actionId;
    private UUID analysisId;
    private UUID organizationId;
    private UUID repositoryId;
    private Integer prNumber;
    private String commitSha;
    private String actionType;
    private String title;
    private String description;
    private String reason;
    private String severity;
    private String confidence;
    private String evidence;
    private List<String> affectedFiles;
    private List<String> affectedSymbols;
    private String status;
    private String sourceFindingId;
    private String sourceFindingType;
    private Map<String, Object> metadata;
}
