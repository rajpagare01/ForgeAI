package com.forgeai.project.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
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

    @JsonAlias({"action_id"})
    private String actionId;

    @JsonAlias({"analysis_id"})
    private UUID analysisId;

    @JsonAlias({"organization_id"})
    private UUID organizationId;

    @JsonAlias({"repository_id"})
    private UUID repositoryId;

    @JsonAlias({"pr_number"})
    private Integer prNumber;

    @JsonAlias({"commit_sha"})
    private String commitSha;

    @JsonAlias({"action_type"})
    private String actionType;

    private String title;
    private String description;
    private String reason;
    private String severity;
    private String confidence;
    private String evidence;

    @JsonAlias({"affected_files"})
    private List<String> affectedFiles;

    @JsonAlias({"affected_symbols"})
    private List<String> affectedSymbols;

    private String status;

    @JsonAlias({"source_finding_id"})
    private String sourceFindingId;

    @JsonAlias({"source_finding_type"})
    private String sourceFindingType;

    private Map<String, Object> metadata;
}
