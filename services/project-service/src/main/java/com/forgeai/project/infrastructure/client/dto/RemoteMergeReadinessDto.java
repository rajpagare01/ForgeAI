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
public class RemoteMergeReadinessDto {
    private String analysisId;
    private String organizationId;
    private String entityId;
    private String riskLevel;
    private Double riskScore;
    private Map<String, Object> mergeReadiness;
    private List<Map<String, Object>> reviewFindings;
}
