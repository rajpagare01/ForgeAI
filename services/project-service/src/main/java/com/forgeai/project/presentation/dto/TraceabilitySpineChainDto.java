package com.forgeai.project.presentation.dto;

import com.forgeai.project.domain.model.RequirementHealth;
import com.forgeai.project.domain.model.TraceabilityGap;
import com.forgeai.project.infrastructure.client.dto.*;

import java.util.List;

public record TraceabilitySpineChainDto(
        RequirementResponse requirement,
        List<TaskResponse> tasks,
        List<RemoteCommitDto> commits,
        List<RemotePullRequestDto> pullRequests,
        List<RemoteTestRunDto> testRuns,
        List<RemoteRequirementTestTraceDto.RemoteTestCaseTraceSummary> testCases,
        List<RemoteAiAnalysisDto> aiAnalyses,
        List<RemoteEngineeringActionDto> remediationActions,
        RequirementHealth health,
        List<TraceabilityGap> gaps
) {}
