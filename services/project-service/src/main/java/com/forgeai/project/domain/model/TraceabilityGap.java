package com.forgeai.project.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceabilityGap {
    private GapType gapType;
    private String artifactId;
    private String artifactName;
    private String severity; // CRITICAL, HIGH, MEDIUM, LOW
    private String evidence;
    private String recommendation;
}
