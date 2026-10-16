package com.forgeai.project.presentation.dto;

import com.forgeai.project.domain.model.RequirementHealth;
import com.forgeai.project.domain.model.TraceabilityGap;
import com.forgeai.project.infrastructure.client.dto.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * TaskTraceabilityResponse
 * <p>
 * Task-centric continuous delivery traceability record.
 * Aggregates task, parent requirement, linked code artifacts, related commits,
 * pull requests, CI test runs, test case summaries, AI risk evaluations,
 * remediation actions, applicable gaps, and granular link provenance.
 */
public record TaskTraceabilityResponse(
        UUID taskId,
        UUID organizationId,
        UUID projectId,
        TaskResponse task,
        RequirementResponse requirement,
        List<CodeLinkResponse> codeLinks,
        List<RemoteCommitDto> commits,
        List<RemotePullRequestDto> pullRequests,
        List<RemoteTestRunDto> testRuns,
        List<RemoteRequirementTestTraceDto.RemoteTestCaseTraceSummary> testCases,
        List<RemoteAiAnalysisDto> aiAnalyses,
        List<RemoteEngineeringActionDto> remediationActions,
        List<TraceabilityGap> gaps,
        List<TraceabilityLinkDto> links,
        String evidenceStatus,
        RequirementHealth health,
        Instant generatedAt
) {}
