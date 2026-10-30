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
public class RemoteAiAnalysisDto {
    @JsonAlias({"analysis_id", "analysisId"})
    private UUID id;

    @JsonAlias({"organization_id"})
    private UUID organizationId;

    @JsonAlias({"entity_type"})
    private String entityType;

    @JsonAlias({"entity_id"})
    private String entityId;

    private String provider;

    @JsonAlias({"model_version"})
    private String modelVersion;

    @JsonAlias({"prompt_version"})
    private String promptVersion;

    private String summary;

    @JsonAlias({"risk_level"})
    private String riskLevel;

    @JsonAlias({"risk_score"})
    private Double riskScore;

    private String confidence;

    @JsonAlias({"risk_factors"})
    private List<Map<String, Object>> riskFactors;

    @JsonAlias({"impact_metadata"})
    private Map<String, Object> impactMetadata;

    @JsonAlias({"test_strategy"})
    private Map<String, Object> testStrategy;

    @JsonAlias({"test_effectiveness"})
    private Map<String, Object> testEffectiveness;

    @JsonAlias({"merge_readiness"})
    private Map<String, Object> mergeReadiness;

    @JsonAlias({"review_findings"})
    private List<Map<String, Object>> reviewFindings;

    @JsonAlias({"security_assessment"})
    private Map<String, Object> securityAssessment;

    @JsonAlias({"reliability_assessment"})
    private Map<String, Object> reliabilityAssessment;

    @JsonAlias({"unified_risk"})
    private Map<String, Object> unifiedRisk;

    @JsonAlias({"fallback_used"})
    private Boolean fallbackUsed;

    @JsonAlias({"trace_id"})
    private String traceId;
}
