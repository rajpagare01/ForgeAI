package com.forgeai.project.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.project.domain.exception.DuplicateResourceException;
import com.forgeai.project.domain.exception.ResourceNotFoundException;
import com.forgeai.project.domain.model.*;
import com.forgeai.project.domain.repository.*;
import com.forgeai.project.infrastructure.client.AiServiceClient;
import com.forgeai.project.infrastructure.client.SourceControlClient;
import com.forgeai.project.infrastructure.client.TestingServiceClient;
import com.forgeai.project.infrastructure.client.dto.*;
import com.forgeai.project.presentation.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TraceabilityAggregationService {

    private final ProjectRepository projectRepository;
    private final RequirementRepository requirementRepository;
    private final TaskRepository taskRepository;
    private final CodeLinkRepository codeLinkRepository;
    private final ProjectRepositoryLinkRepository projectRepositoryLinkRepository;
    private final TraceabilityLinkRepository traceabilityLinkRepository;
    private final TraceabilitySnapshotRepository traceabilitySnapshotRepository;

    private final SourceControlClient sourceControlClient;
    private final TestingServiceClient testingServiceClient;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ProjectTraceabilityResponse getProjectTraceability(UUID organizationId, UUID projectId) {
        validateTenant(organizationId);
        Project project = projectRepository.findByIdAndOrganizationId(projectId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: id=" + projectId + " in org=" + organizationId));

        // 1. Fetch domain items
        List<Requirement> requirements = requirementRepository.findAllByOrganizationIdAndProjectId(organizationId, projectId);
        List<Task> tasks = taskRepository.findAllByOrganizationIdAndProjectId(organizationId, projectId);
        List<ProjectRepositoryLink> repoLinks = projectRepositoryLinkRepository.findByProjectId(organizationId, projectId);
        List<TraceabilityLink> persistedLinks = traceabilityLinkRepository.findByProjectId(organizationId, projectId);

        // Map tasks by requirement ID
        Map<UUID, List<Task>> tasksByReqId = tasks.stream()
                .filter(t -> t.getRequirementId() != null)
                .collect(Collectors.groupingBy(Task::getRequirementId));

        // Fetch task code links
        Map<UUID, List<CodeLink>> codeLinksByTaskId = new HashMap<>();
        for (Task t : tasks) {
            codeLinksByTaskId.put(t.getId(), codeLinkRepository.findAllByOrganizationIdAndTaskId(organizationId, t.getId()));
        }

        // 2. Fetch external artifacts across connected repositories
        List<RemotePullRequestDto> allPrs = new ArrayList<>();
        List<RemoteCommitDto> allCommits = new ArrayList<>();
        Map<UUID, RemoteRepositoryDto> repoDetails = new HashMap<>();

        for (ProjectRepositoryLink rl : repoLinks) {
            sourceControlClient.getRepository(organizationId, rl.getRepositoryId())
                    .ifPresent(r -> repoDetails.put(rl.getRepositoryId(), r));
            allPrs.addAll(sourceControlClient.listPullRequests(organizationId, rl.getRepositoryId()));
            allCommits.addAll(sourceControlClient.listCommits(organizationId, rl.getRepositoryId()));
        }

        // 3. Fetch Test data for commits/PRs and requirements
        Map<String, List<RemoteTestRunDto>> testRunsBySha = new HashMap<>();
        Map<String, RemoteRequirementTestTraceDto> reqTestsByKey = new HashMap<>();

        for (Requirement r : requirements) {
            testingServiceClient.getTraceabilityForRequirement(organizationId, r.getIdentifier())
                    .ifPresent(t -> reqTestsByKey.put(r.getIdentifier(), t));
        }

        for (RemotePullRequestDto pr : allPrs) {
            if (pr.getHeadSha() != null && !testRunsBySha.containsKey(pr.getHeadSha())) {
                List<RemoteTestRunDto> runs = testingServiceClient.listTestRuns(organizationId, pr.getRepositoryId(), pr.getHeadSha());
                testRunsBySha.put(pr.getHeadSha(), runs);
            }
        }

        // 4. Fetch AI analyses, risk evaluations, and remediation actions for PRs
        Map<String, List<RemoteAiAnalysisDto>> aiAnalysesByPrKey = new HashMap<>();
        Map<UUID, List<RemoteEngineeringActionDto>> actionsByAnalysisId = new HashMap<>();

        for (RemotePullRequestDto pr : allPrs) {
            String prKey = pr.getRepositoryId() + "#" + pr.getPrNumber();
            List<RemoteAiAnalysisDto> analyses = aiServiceClient.getAnalysesForPullRequest(organizationId, pr.getRepositoryId(), pr.getPrNumber());
            aiAnalysesByPrKey.put(prKey, analyses);
            for (RemoteAiAnalysisDto analysis : analyses) {
                if (analysis.getId() != null) {
                    List<RemoteEngineeringActionDto> actions = aiServiceClient.getEngineeringActions(organizationId, analysis.getId());
                    actionsByAnalysisId.put(analysis.getId(), actions);
                }
            }
        }

        // 5. Build full spine chains
        List<TraceabilitySpineChainDto> chains = new ArrayList<>();
        List<TraceabilityGap> allGaps = new ArrayList<>();

        for (Requirement req : requirements) {
            List<Task> reqTasks = tasksByReqId.getOrDefault(req.getId(), Collections.emptyList());
            Set<RemoteCommitDto> chainCommits = new LinkedHashSet<>();
            Set<RemotePullRequestDto> chainPrs = new LinkedHashSet<>();
            Set<RemoteTestRunDto> chainTestRuns = new LinkedHashSet<>();
            List<RemoteRequirementTestTraceDto.RemoteTestCaseTraceSummary> chainTestCases = new ArrayList<>();
            Set<RemoteAiAnalysisDto> chainAnalyses = new LinkedHashSet<>();
            Set<RemoteEngineeringActionDto> chainActions = new LinkedHashSet<>();

            RemoteRequirementTestTraceDto testTrace = reqTestsByKey.get(req.getIdentifier());
            if (testTrace != null && testTrace.getTests() != null) {
                chainTestCases.addAll(testTrace.getTests());
            }

            String reqKey = req.getIdentifier().toLowerCase();
            for (RemotePullRequestDto pr : allPrs) {
                boolean prMatches = matchesText(pr.getTitle(), reqKey) ||
                        matchesText(pr.getBody(), reqKey) ||
                        matchesText(pr.getHeadBranch(), reqKey);

                if (!prMatches) {
                    for (Task t : reqTasks) {
                        String tKey = t.getTaskKey().toLowerCase();
                        if (matchesText(pr.getTitle(), tKey) || matchesText(pr.getBody(), tKey) || matchesText(pr.getHeadBranch(), tKey)) {
                            prMatches = true;
                            break;
                        }
                    }
                }

                if (!prMatches) {
                    for (Task t : reqTasks) {
                        List<CodeLink> clList = codeLinksByTaskId.getOrDefault(t.getId(), Collections.emptyList());
                        for (CodeLink cl : clList) {
                            if (cl.getLinkType() == CodeLinkType.PULL_REQUEST &&
                                    cl.getExternalRef().equals(String.valueOf(pr.getPrNumber()))) {
                                prMatches = true;
                                break;
                            }
                        }
                        if (prMatches) break;
                    }
                }

                if (prMatches) {
                    chainPrs.add(pr);
                    if (pr.getHeadSha() != null && testRunsBySha.containsKey(pr.getHeadSha())) {
                        chainTestRuns.addAll(testRunsBySha.get(pr.getHeadSha()));
                    }
                    String prKey = pr.getRepositoryId() + "#" + pr.getPrNumber();
                    List<RemoteAiAnalysisDto> analyses = aiAnalysesByPrKey.getOrDefault(prKey, Collections.emptyList());
                    chainAnalyses.addAll(analyses);
                    for (RemoteAiAnalysisDto a : analyses) {
                        if (a.getId() != null) {
                            chainActions.addAll(actionsByAnalysisId.getOrDefault(a.getId(), Collections.emptyList()));
                        }
                    }
                }
            }

            for (RemoteCommitDto commit : allCommits) {
                boolean commitMatches = matchesText(commit.getMessage(), reqKey);
                if (!commitMatches) {
                    for (Task t : reqTasks) {
                        if (matchesText(commit.getMessage(), t.getTaskKey().toLowerCase())) {
                            commitMatches = true;
                            break;
                        }
                    }
                }
                if (commitMatches) {
                    chainCommits.add(commit);
                    if (commit.getCommitSha() != null && testRunsBySha.containsKey(commit.getCommitSha())) {
                        chainTestRuns.addAll(testRunsBySha.get(commit.getCommitSha()));
                    }
                }
            }

            List<TraceabilityGap> reqGaps = detectRequirementGaps(req, reqTasks, chainPrs, chainTestRuns, chainTestCases, chainAnalyses, chainActions);
            allGaps.addAll(reqGaps);

            RequirementHealth reqHealth = computeRequirementHealth(req, reqTasks, chainPrs, chainTestRuns, chainTestCases, chainAnalyses, chainActions);

            List<TaskResponse> taskResponses = reqTasks.stream().map(TaskResponse::from).collect(Collectors.toList());
            chains.add(new TraceabilitySpineChainDto(
                    RequirementResponse.from(req),
                    taskResponses,
                    new ArrayList<>(chainCommits),
                    new ArrayList<>(chainPrs),
                    new ArrayList<>(chainTestRuns),
                    chainTestCases,
                    new ArrayList<>(chainAnalyses),
                    new ArrayList<>(chainActions),
                    reqHealth,
                    reqGaps
            ));
        }

        // 6. Detect project-level gaps
        List<TraceabilityGap> projectGaps = detectProjectGaps(tasks, allPrs, chains);
        allGaps.addAll(projectGaps);

        // 7. Compute deterministic ProjectHealth
        ProjectHealth projectHealth = computeProjectHealth(requirements, tasks, chains, allGaps, allPrs);

        // 8. Map repositories and links
        List<ProjectRepositoryLinkDto> repoDtos = repoLinks.stream()
                .map(ProjectRepositoryLinkDto::from)
                .collect(Collectors.toList());

        List<TraceabilityLinkDto> linkDtos = persistedLinks.stream()
                .map(TraceabilityLinkDto::from)
                .collect(Collectors.toList());

        return new ProjectTraceabilityResponse(
                projectId,
                organizationId,
                projectHealth,
                allGaps,
                chains,
                repoDtos,
                linkDtos,
                Instant.now()
        );
    }

    @Transactional(readOnly = true)
    public ProjectHealth getProjectHealth(UUID organizationId, UUID projectId) {
        return getProjectTraceability(organizationId, projectId).health();
    }

    @Transactional(readOnly = true)
    public List<TraceabilityGap> getProjectGaps(UUID organizationId, UUID projectId) {
        return getProjectTraceability(organizationId, projectId).gaps();
    }

    @Transactional(readOnly = true)
    public TraceabilitySpineChainDto getRequirementTraceability(UUID organizationId, UUID requirementId) {
        validateTenant(organizationId);
        Requirement req = requirementRepository.findByIdAndOrganizationId(requirementId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Requirement not found: id=" + requirementId + " in org=" + organizationId));

        ProjectTraceabilityResponse full = getProjectTraceability(organizationId, req.getProjectId());
        return full.chains().stream()
                .filter(c -> c.requirement().id().equals(requirementId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Traceability chain not found for requirement: " + requirementId));
    }

    @Transactional
    public int syncTraceability(UUID organizationId, UUID projectId) {
        validateTenant(organizationId);
        projectRepository.findByIdAndOrganizationId(projectId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: id=" + projectId));

        List<Requirement> requirements = requirementRepository.findAllByOrganizationIdAndProjectId(organizationId, projectId);
        List<Task> tasks = taskRepository.findAllByOrganizationIdAndProjectId(organizationId, projectId);
        List<ProjectRepositoryLink> repoLinks = projectRepositoryLinkRepository.findByProjectId(organizationId, projectId);

        int createdCount = 0;

        for (Task task : tasks) {
            if (task.getRequirementId() != null) {
                boolean exists = traceabilityLinkRepository.exists(
                        organizationId, projectId,
                        TraceabilityType.REQUIREMENT, task.getRequirementId().toString(),
                        TraceabilityType.TASK, task.getId().toString(),
                        LinkOrigin.EXPLICIT_USER_LINK.name()
                );
                if (!exists) {
                    TraceabilityLink link = TraceabilityLink.builder()
                            .id(UUID.randomUUID())
                            .organizationId(organizationId)
                            .projectId(projectId)
                            .sourceType(TraceabilityType.REQUIREMENT)
                            .sourceId(task.getRequirementId().toString())
                            .targetType(TraceabilityType.TASK)
                            .targetId(task.getId().toString())
                            .linkOrigin(LinkOrigin.EXPLICIT_USER_LINK)
                            .confidence(ConfidenceLevel.HIGH)
                            .evidence("task_requirement_id_fk")
                            .active(true)
                            .createdAt(Instant.now())
                            .build();
                    traceabilityLinkRepository.save(link);
                    createdCount++;
                }
            }

            List<CodeLink> codeLinks = codeLinkRepository.findAllByOrganizationIdAndTaskId(organizationId, task.getId());
            for (CodeLink cl : codeLinks) {
                TraceabilityType targetType = switch (cl.getLinkType()) {
                    case PULL_REQUEST -> TraceabilityType.PULL_REQUEST;
                    case COMMIT -> TraceabilityType.COMMIT;
                    default -> TraceabilityType.CODE;
                };
                boolean exists = traceabilityLinkRepository.exists(
                        organizationId, projectId,
                        TraceabilityType.TASK, task.getId().toString(),
                        targetType, cl.getExternalRef(),
                        LinkOrigin.COMMIT_MESSAGE.name()
                );
                if (!exists) {
                    TraceabilityLink link = TraceabilityLink.builder()
                            .id(UUID.randomUUID())
                            .organizationId(organizationId)
                            .projectId(projectId)
                            .sourceType(TraceabilityType.TASK)
                            .sourceId(task.getId().toString())
                            .targetType(targetType)
                            .targetId(cl.getExternalRef())
                            .externalReference(cl.getExternalRef())
                            .linkOrigin(LinkOrigin.COMMIT_MESSAGE)
                            .confidence(ConfidenceLevel.HIGH)
                            .evidence("code_link_id=" + cl.getId())
                            .active(true)
                            .createdAt(Instant.now())
                            .build();
                    traceabilityLinkRepository.save(link);
                    createdCount++;
                }
            }
        }

        for (ProjectRepositoryLink repoLink : repoLinks) {
            List<RemotePullRequestDto> prs = sourceControlClient.listPullRequests(organizationId, repoLink.getRepositoryId());
            for (RemotePullRequestDto pr : prs) {
                for (Task task : tasks) {
                    if (matchesText(pr.getTitle(), task.getTaskKey().toLowerCase()) ||
                            matchesText(pr.getBody(), task.getTaskKey().toLowerCase()) ||
                            matchesText(pr.getHeadBranch(), task.getTaskKey().toLowerCase())) {
                        boolean exists = traceabilityLinkRepository.exists(
                                organizationId, projectId,
                                TraceabilityType.TASK, task.getId().toString(),
                                TraceabilityType.PULL_REQUEST, String.valueOf(pr.getPrNumber()),
                                LinkOrigin.PR_REFERENCE.name()
                        );
                        if (!exists) {
                            TraceabilityLink link = TraceabilityLink.builder()
                                    .id(UUID.randomUUID())
                                    .organizationId(organizationId)
                                    .projectId(projectId)
                                    .sourceType(TraceabilityType.TASK)
                                    .sourceId(task.getId().toString())
                                    .targetType(TraceabilityType.PULL_REQUEST)
                                    .targetId(String.valueOf(pr.getPrNumber()))
                                    .repositoryId(pr.getRepositoryId())
                                    .externalReference("PR #" + pr.getPrNumber())
                                    .linkOrigin(LinkOrigin.PR_REFERENCE)
                                    .confidence(ConfidenceLevel.HIGH)
                                    .evidence("repo_id=" + pr.getRepositoryId() + ";pr_number=" + pr.getPrNumber())
                                    .active(true)
                                    .createdAt(Instant.now())
                                    .build();
                            traceabilityLinkRepository.save(link);
                            createdCount++;
                        }
                    }
                }
            }
        }

        log.info("Traceability sync completed for project {}: {} new links registered", projectId, createdCount);
        return createdCount;
    }

    @Transactional
    public TraceabilityLink createLink(UUID organizationId, UUID projectId, CreateTraceabilityLinkRequest request) {
        validateTenant(organizationId);
        projectRepository.findByIdAndOrganizationId(projectId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: id=" + projectId));

        TraceabilityLink link = TraceabilityLink.builder()
                .id(UUID.randomUUID())
                .organizationId(organizationId)
                .projectId(projectId)
                .sourceType(request.sourceType())
                .sourceId(request.sourceId())
                .targetType(request.targetType())
                .targetId(request.targetId())
                .repositoryId(request.repositoryId())
                .externalReference(request.externalReference())
                .linkOrigin(request.linkOrigin() != null ? request.linkOrigin() : LinkOrigin.EXPLICIT_USER_LINK)
                .confidence(request.confidence() != null ? request.confidence() : ConfidenceLevel.HIGH)
                .evidence(request.evidence())
                .active(true)
                .createdAt(Instant.now())
                .build();

        return traceabilityLinkRepository.save(link);
    }

    @Transactional
    public void deleteLink(UUID organizationId, UUID linkId) {
        validateTenant(organizationId);
        traceabilityLinkRepository.deleteById(organizationId, linkId);
    }

    @Transactional
    public ProjectRepositoryLink linkRepository(UUID organizationId, UUID projectId, UUID repositoryId, String repositoryName) {
        validateTenant(organizationId);
        projectRepository.findByIdAndOrganizationId(projectId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: id=" + projectId));

        if (projectRepositoryLinkRepository.existsByProjectIdAndRepositoryId(organizationId, projectId, repositoryId)) {
            throw new DuplicateResourceException("Repository " + repositoryId + " is already linked to project " + projectId);
        }

        String repoName = repositoryName;
        if (repoName == null || repoName.isBlank()) {
            RemoteRepositoryDto repoDto = sourceControlClient.getRepository(organizationId, repositoryId).orElse(null);
            repoName = (repoDto != null && repoDto.getFullName() != null) ? repoDto.getFullName() : "Repository-" + repositoryId.toString().substring(0, 8);
        }

        ProjectRepositoryLink link = ProjectRepositoryLink.builder()
                .id(UUID.randomUUID())
                .organizationId(organizationId)
                .projectId(projectId)
                .repositoryId(repositoryId)
                .repositoryName(repoName)
                .createdAt(Instant.now())
                .build();

        return projectRepositoryLinkRepository.save(link);
    }

    @Transactional
    public void unlinkRepository(UUID organizationId, UUID projectId, UUID repositoryId) {
        validateTenant(organizationId);
        projectRepositoryLinkRepository.deleteByProjectIdAndRepositoryId(organizationId, projectId, repositoryId);
    }

    @Transactional(readOnly = true)
    public List<ProjectRepositoryLinkDto> listProjectRepositories(UUID organizationId, UUID projectId) {
        validateTenant(organizationId);
        return projectRepositoryLinkRepository.findByProjectId(organizationId, projectId).stream()
                .map(ProjectRepositoryLinkDto::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public TraceabilitySnapshot createSnapshot(UUID organizationId, UUID projectId, String snapshotName, Integer prNumber, String commitSha, UUID repositoryId) {
        validateTenant(organizationId);
        ProjectTraceabilityResponse response = getProjectTraceability(organizationId, projectId);

        List<TraceabilitySnapshot> existing = traceabilitySnapshotRepository.findByProjectId(organizationId, projectId);
        int nextVersion = existing.size() + 1;
        String name = (snapshotName != null && !snapshotName.isBlank()) ? snapshotName : "Snapshot v" + nextVersion;

        String dataJson;
        try {
            dataJson = objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            dataJson = "{}";
        }

        TraceabilitySnapshot snapshot = TraceabilitySnapshot.builder()
                .id(UUID.randomUUID())
                .organizationId(organizationId)
                .projectId(projectId)
                .snapshotName(name)
                .prNumber(prNumber)
                .commitSha(commitSha)
                .repositoryId(repositoryId)
                .snapshotData(dataJson)
                .createdAt(Instant.now())
                .build();

        return traceabilitySnapshotRepository.save(snapshot);
    }

    @Transactional(readOnly = true)
    public List<TraceabilitySnapshotResponse> listSnapshots(UUID organizationId, UUID projectId) {
        validateTenant(organizationId);
        return traceabilitySnapshotRepository.findByProjectId(organizationId, projectId).stream()
                .map(TraceabilitySnapshotResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TraceabilitySnapshotResponse getSnapshot(UUID organizationId, UUID snapshotId) {
        validateTenant(organizationId);
        return traceabilitySnapshotRepository.findById(organizationId, snapshotId)
                .map(TraceabilitySnapshotResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Snapshot not found: id=" + snapshotId));
    }

    // ======================================================================
    // Deterministic Gap Detection Rules
    // ======================================================================

    private List<TraceabilityGap> detectRequirementGaps(
            Requirement req,
            List<Task> tasks,
            Set<RemotePullRequestDto> prs,
            Set<RemoteTestRunDto> testRuns,
            List<RemoteRequirementTestTraceDto.RemoteTestCaseTraceSummary> testCases,
            Set<RemoteAiAnalysisDto> analyses,
            Set<RemoteEngineeringActionDto> actions
    ) {
        List<TraceabilityGap> gaps = new ArrayList<>();

        if (tasks.isEmpty()) {
            gaps.add(TraceabilityGap.builder()
                    .gapType(GapType.ORPHAN_REQUIREMENT)
                    .artifactId(req.getId().toString())
                    .artifactName(req.getIdentifier())
                    .evidence("Requirement has 0 linked tasks")
                    .severity("MEDIUM")
                    .recommendation("Create and link tasks to this requirement to begin implementation.")
                    .build());
        }

        for (Task t : tasks) {
            if (t.getStatus() == TaskStatus.IN_PROGRESS || t.getStatus() == TaskStatus.IN_REVIEW || t.getStatus() == TaskStatus.DONE) {
                if (prs.isEmpty()) {
                    gaps.add(TraceabilityGap.builder()
                            .gapType(GapType.TASK_WITHOUT_CODE)
                            .artifactId(t.getId().toString())
                            .artifactName(t.getTaskKey())
                            .evidence("Task status is " + t.getStatus() + " with 0 linked PRs or commits")
                            .severity("MEDIUM")
                            .recommendation("Link the branch or pull request implementing this task.")
                            .build());
                }
            }
        }

        boolean hasPassingTests = testCases.stream().anyMatch(tc -> "PASSED".equalsIgnoreCase(tc.getLatestStatus())) ||
                testRuns.stream().anyMatch(tr -> "COMPLETED".equalsIgnoreCase(tr.getStatus()) && (tr.getFailedTests() == null || tr.getFailedTests() == 0));
        if (!hasPassingTests && (testCases.isEmpty() && testRuns.isEmpty())) {
            gaps.add(TraceabilityGap.builder()
                    .gapType(GapType.REQUIREMENT_WITHOUT_TEST)
                    .artifactId(req.getId().toString())
                    .artifactName(req.getIdentifier())
                    .evidence("0 test cases or test runs linked")
                    .severity("HIGH")
                    .recommendation("Add test cases tagged with @" + req.getIdentifier() + " to satisfy automated verification.")
                    .build());
        }

        for (RemotePullRequestDto pr : prs) {
            boolean hasEvidence = testRuns.stream().anyMatch(tr -> "COMPLETED".equalsIgnoreCase(tr.getStatus()));
            if (!hasEvidence && Boolean.FALSE.equals(pr.getIsMerged())) {
                gaps.add(TraceabilityGap.builder()
                        .gapType(GapType.PR_WITHOUT_TEST_EVIDENCE)
                        .artifactId(String.valueOf(pr.getPrNumber()))
                        .artifactName("PR #" + pr.getPrNumber())
                        .evidence("PR #" + pr.getPrNumber() + " has no completed test runs")
                        .severity("HIGH")
                        .recommendation("Trigger a CI test workflow run on the PR branch.")
                        .build());
            }
        }

        for (RemoteAiAnalysisDto analysis : analyses) {
            if (analysis.getRiskScore() != null && analysis.getRiskScore() >= 0.70) {
                gaps.add(TraceabilityGap.builder()
                        .gapType(GapType.HIGH_RISK_REQUIREMENT)
                        .artifactId(req.getId().toString())
                        .artifactName(req.getIdentifier())
                        .evidence("AI risk score: " + analysis.getRiskScore() + ", level: " + analysis.getRiskLevel())
                        .severity("HIGH")
                        .recommendation("Review security and architectural findings before approval.")
                        .build());
            }
        }

        boolean hasBlockedTask = tasks.stream().anyMatch(t -> t.getStatus() == TaskStatus.BLOCKED);
        boolean hasBlockedPr = prs.stream().anyMatch(pr -> {
            String prKey = pr.getRepositoryId() + "#" + pr.getPrNumber();
            List<RemoteAiAnalysisDto> al = analyses.stream().filter(a -> prKey.equals(a.getEntityId())).toList();
            return al.stream().anyMatch(a -> a.getMergeReadiness() != null &&
                    "BLOCKED".equalsIgnoreCase(String.valueOf(a.getMergeReadiness().get("status"))));
        });

        if (hasBlockedTask || hasBlockedPr) {
            gaps.add(TraceabilityGap.builder()
                    .gapType(GapType.BLOCKED_REQUIREMENT)
                    .artifactId(req.getId().toString())
                    .artifactName(req.getIdentifier())
                    .evidence("Blocked tasks or blocked merge readiness present")
                    .severity("HIGH")
                    .recommendation("Resolve blocking issues and address failing checks.")
                    .build());
        }

        long unresolvedActions = actions.stream()
                .filter(a -> !"APPLIED".equalsIgnoreCase(a.getStatus()) &&
                        !"RESOLVED".equalsIgnoreCase(a.getStatus()) &&
                        !"CLOSED".equalsIgnoreCase(a.getStatus()))
                .count();
        if (unresolvedActions > 0) {
            gaps.add(TraceabilityGap.builder()
                    .gapType(GapType.UNRESOLVED_REMEDIATION)
                    .artifactId(req.getId().toString())
                    .artifactName(req.getIdentifier())
                    .evidence(unresolvedActions + " unresolved remediation action(s)")
                    .severity("MEDIUM")
                    .recommendation("Review and approve pending AI remediation patches.")
                    .build());
        }

        return gaps;
    }

    private List<TraceabilityGap> detectProjectGaps(
            List<Task> tasks,
            List<RemotePullRequestDto> allPrs,
            List<TraceabilitySpineChainDto> chains
    ) {
        List<TraceabilityGap> gaps = new ArrayList<>();

        for (Task t : tasks) {
            if (t.getRequirementId() == null) {
                gaps.add(TraceabilityGap.builder()
                        .gapType(GapType.ORPHAN_TASK)
                        .artifactId(t.getId().toString())
                        .artifactName(t.getTaskKey())
                        .evidence("Task requirementId is null")
                        .severity("LOW")
                        .recommendation("Assign this task to a requirement to ensure delivery alignment.")
                        .build());
            }
        }

        Set<Integer> chainPrNumbers = chains.stream()
                .flatMap(c -> c.pullRequests().stream())
                .map(RemotePullRequestDto::getPrNumber)
                .collect(Collectors.toSet());

        for (RemotePullRequestDto pr : allPrs) {
            if (!chainPrNumbers.contains(pr.getPrNumber())) {
                gaps.add(TraceabilityGap.builder()
                        .gapType(GapType.PR_WITHOUT_REQUIREMENT)
                        .artifactId(String.valueOf(pr.getPrNumber()))
                        .artifactName("PR #" + pr.getPrNumber())
                        .evidence("PR #" + pr.getPrNumber() + " not linked to any requirement in delivery spine")
                        .severity("MEDIUM")
                        .recommendation("Mention requirement or task key in the PR title or description.")
                        .build());
            }
        }

        return gaps;
    }

    // ======================================================================
    // Deterministic Project Health & Requirement Health Calculations
    // ======================================================================

    private ProjectHealth computeProjectHealth(
            List<Requirement> requirements,
            List<Task> tasks,
            List<TraceabilitySpineChainDto> chains,
            List<TraceabilityGap> allGaps,
            List<RemotePullRequestDto> allPrs
    ) {
        int totalRequirements = requirements.size();
        int openTasks = (int) tasks.stream().filter(t -> t.getStatus() != TaskStatus.DONE).count();
        int blockedTasks = (int) tasks.stream().filter(t -> t.getStatus() == TaskStatus.BLOCKED).count();
        int activePrs = (int) allPrs.stream().filter(pr -> !Boolean.TRUE.equals(pr.getIsMerged())).count();

        int requirementsCompleted = (int) chains.stream()
                .filter(c -> c.health() != null && c.health().getStatus() == RequirementHealthStatus.COMPLETED)
                .count();
        int requirementsAtRisk = (int) chains.stream()
                .filter(c -> c.health() != null && c.health().getStatus() == RequirementHealthStatus.AT_RISK)
                .count();
        int reqsWithoutImpl = (int) chains.stream()
                .filter(c -> c.tasks().isEmpty() && c.commits().isEmpty() && c.pullRequests().isEmpty())
                .count();
        int reqsWithoutTests = (int) chains.stream()
                .filter(c -> c.testCases().isEmpty() && c.testRuns().isEmpty())
                .count();

        long highRiskCount = allGaps.stream().filter(g -> g.getGapType() == GapType.HIGH_RISK_REQUIREMENT).count();
        long blockedCount = allGaps.stream().filter(g -> g.getGapType() == GapType.BLOCKED_REQUIREMENT).count();
        long unresolvedRemediations = allGaps.stream().filter(g -> g.getGapType() == GapType.UNRESOLVED_REMEDIATION).count();

        double score = 100.0;
        for (TraceabilityGap gap : allGaps) {
            switch (gap.getSeverity().toUpperCase()) {
                case "HIGH" -> score -= 15.0;
                case "MEDIUM" -> score -= 8.0;
                case "LOW" -> score -= 3.0;
            }
        }
        score = Math.max(0.0, Math.min(100.0, Math.round(score * 10.0) / 10.0));

        ProjectHealthStatus status;
        List<String> reasons = new ArrayList<>();

        if (blockedCount > 0 || blockedTasks > 0) {
            status = ProjectHealthStatus.RED;
            reasons.add(blockedCount + " requirement(s) or " + blockedTasks + " task(s) are currently blocked.");
        } else if (score < 50.0) {
            status = ProjectHealthStatus.RED;
            reasons.add("Traceability health score is critically low (" + score + "/100).");
        } else if (score < 80.0 || highRiskCount > 0 || reqsWithoutTests > 0) {
            status = ProjectHealthStatus.AMBER;
            if (highRiskCount > 0) reasons.add(highRiskCount + " requirement(s) have high AI risk.");
            if (reqsWithoutTests > 0) reasons.add(reqsWithoutTests + " requirement(s) lack automated tests.");
            if (unresolvedRemediations > 0) reasons.add(unresolvedRemediations + " open AI remediation action(s).");
            if (reasons.isEmpty()) reasons.add("Traceability gaps require attention (score " + score + "/100).");
        } else {
            status = ProjectHealthStatus.GREEN;
            reasons.add("All requirements have continuous delivery traceability and verified test evidence.");
        }

        return ProjectHealth.builder()
                .status(status)
                .healthScore(score)
                .totalRequirements(totalRequirements)
                .requirementsCompleted(requirementsCompleted)
                .requirementsAtRisk(requirementsAtRisk)
                .requirementsWithoutImplementation(reqsWithoutImpl)
                .requirementsWithoutTests(reqsWithoutTests)
                .openTasks(openTasks)
                .blockedTasks(blockedTasks)
                .activePrs(activePrs)
                .failingPrs(0)
                .mergeReadinessBlockers((int) blockedCount)
                .unresolvedSecurityFindings(0)
                .unresolvedReliabilityFindings(0)
                .activeRemediationActions((int) unresolvedRemediations)
                .reasons(reasons)
                .build();
    }

    private RequirementHealth computeRequirementHealth(
            Requirement req,
            List<Task> tasks,
            Set<RemotePullRequestDto> prs,
            Set<RemoteTestRunDto> testRuns,
            List<RemoteRequirementTestTraceDto.RemoteTestCaseTraceSummary> testCases,
            Set<RemoteAiAnalysisDto> analyses,
            Set<RemoteEngineeringActionDto> actions
    ) {
        int tasksCount = tasks.size();
        long completedTasks = tasks.stream().filter(t -> t.getStatus() == TaskStatus.DONE).count();
        int prsCount = prs.size();
        long mergedPrs = prs.stream().filter(pr -> Boolean.TRUE.equals(pr.getIsMerged())).count();
        int testsCount = testCases.size();
        long passingTests = testCases.stream().filter(tc -> "PASSED".equalsIgnoreCase(tc.getLatestStatus())).count();

        double riskScore = analyses.stream()
                .mapToDouble(a -> a.getRiskScore() != null ? a.getRiskScore() : 0.0)
                .max()
                .orElse(0.0);

        String mergeReadiness = prs.stream()
                .flatMap(pr -> analyses.stream().filter(a -> (pr.getRepositoryId() + "#" + pr.getPrNumber()).equals(a.getEntityId())))
                .map(a -> a.getMergeReadiness() != null ? String.valueOf(a.getMergeReadiness().get("status")) : "PENDING")
                .findFirst()
                .orElse("NONE");

        double completeness = 0.0;
        if (tasksCount > 0) completeness += 25.0;
        if (tasksCount > 0 && completedTasks == tasksCount) completeness += 25.0;
        if (testsCount > 0 || !testRuns.isEmpty()) completeness += 25.0;
        if ((passingTests > 0 || testRuns.stream().anyMatch(tr -> "COMPLETED".equalsIgnoreCase(tr.getStatus()))) &&
                (mergedPrs > 0 || "READY".equalsIgnoreCase(mergeReadiness))) {
            completeness += 25.0;
        }

        completeness = Math.min(100.0, completeness);

        String implStatus = tasksCount == 0 ? "NOT_STARTED" : (completedTasks == tasksCount ? "IMPLEMENTED" : "IN_PROGRESS");
        String testStatus = testsCount == 0 && testRuns.isEmpty() ? "NO_TESTS" : (passingTests > 0 ? "TESTS_PASSED" : "TESTS_FAILING");
        String riskStatus = riskScore >= 0.70 ? "HIGH" : (riskScore >= 0.40 ? "MEDIUM" : (riskScore > 0 ? "LOW" : "NO_RISK"));
        String traceabilityStatus = (tasksCount > 0 && (prsCount > 0 || testsCount > 0)) ? "COMPLETE" : (tasksCount > 0 ? "PARTIAL" : "ORPHAN");

        RequirementHealthStatus status;
        if (completeness >= 100.0) {
            status = RequirementHealthStatus.COMPLETED;
        } else if (riskScore >= 0.70 || tasks.stream().anyMatch(t -> t.getStatus() == TaskStatus.BLOCKED)) {
            status = RequirementHealthStatus.AT_RISK;
        } else if (passingTests > 0 && testsCount == passingTests && completeness >= 75.0) {
            status = RequirementHealthStatus.VALIDATED;
        } else if (completedTasks > 0 || prsCount > 0) {
            status = RequirementHealthStatus.IMPLEMENTED;
        } else if (tasks.stream().anyMatch(t -> t.getStatus() == TaskStatus.IN_PROGRESS)) {
            status = RequirementHealthStatus.IN_PROGRESS;
        } else {
            status = RequirementHealthStatus.NOT_STARTED;
        }

        return RequirementHealth.builder()
                .requirementId(req.getId())
                .identifier(req.getIdentifier())
                .title(req.getTitle())
                .status(status)
                .implementationStatus(implStatus)
                .testStatus(testStatus)
                .riskStatus(riskStatus)
                .traceabilityStatus(traceabilityStatus)
                .completenessScore(completeness)
                .linkedTasksCount(tasksCount)
                .linkedPrsCount(prsCount)
                .testsCount(testsCount)
                .activeRiskScore(riskScore)
                .remediationCount(actions.size())
                .build();
    }

    private boolean matchesText(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isBlank()) return false;
        String h = haystack.toLowerCase();
        String n = needle.toLowerCase();
        return h.contains(n) || h.contains(n.replace("-", "")) || h.contains(n.replace("_", ""));
    }

    private void validateTenant(UUID organizationId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must be provided via tenant context");
        }
    }
}
