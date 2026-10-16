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
public class RemoteAiAnalysisDto {
    private UUID id;
    private UUID organizationId;
    private String entityType;
    private String entityId;
    private String provider;
    private String modelVersion;
    private String promptVersion;
    private String summary;
    private String riskLevel;
    private Double riskScore;
    private String confidence;
    private List<Map<String, Object>> riskFactors;
    private Map<String, Object> impactMetadata;
    private Map<String, Object> testStrategy;
    private Map<String, Object> testEffectiveness;
    private Map<String, Object> mergeReadiness;
    private List<Map<String, Object>> reviewFindings;
    private Map<String, Object> securityAssessment;
    private Map<String, Object> reliabilityAssessment;
    private Map<String, Object> unifiedRisk;
    private Boolean fallbackUsed;
    private String traceId;
}
