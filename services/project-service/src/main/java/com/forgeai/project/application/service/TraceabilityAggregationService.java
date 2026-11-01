package com.forgeai.project.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.project.domain.exception.AccessDeniedException;
import com.forgeai.project.domain.exception.DuplicateResourceException;
import com.forgeai.project.domain.exception.ResourceNotFoundException;
import com.forgeai.project.domain.model.*;
import com.forgeai.project.domain.repository.*;
import com.forgeai.project.infrastructure.client.AiServiceClient;
import com.forgeai.project.infrastructure.client.SourceControlClient;
import com.forgeai.project.infrastructure.client.TestingServiceClient;
import com.forgeai.project.infrastructure.client.dto.*;
import com.forgeai.project.infrastructure.persistence.entity.WorkspaceJpaEntity;
import com.forgeai.project.infrastructure.persistence.repository.SpringDataWorkspaceRepository;
import com.forgeai.project.presentation.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * TraceabilityAggregationService
 * <p>
 * Aggregates the authoritative cross-service continuous delivery spine:
 * Requirement -> Task -> Code Artifact -> Commit -> Pull Request -> Test Run -> AI Analysis -> Risk -> Merge Readiness -> Remediation.
 * <p>
 * Formulates deterministic gap detection and reproducible project health scores without LLM hallucinations.
 */
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
    private final SpringDataWorkspaceRepository workspaceRepository;

    private final SourceControlClient sourceControlClient;
    private final TestingServiceClient testingServiceClient;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuditService auditService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.forgeai.project.infrastructure.metrics.ProjectMetrics projectMetrics;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.forgeai.project.infrastructure.logging.StructuredLogger structuredLogger;

    /**
     * Request-scoped cache container to eliminate N+1 duplicate downstream HTTP queries.
     */
    private static class RequestAggregationCache {
        final Map<UUID, Optional<RemoteRepositoryDto>> repoCache = new ConcurrentHashMap<>();
        final Map<UUID, List<RemotePullRequestDto>> prsByRepoCache = new ConcurrentHashMap<>();
        final Map<UUID, List<RemoteCommitDto>> commitsByRepoCache = new ConcurrentHashMap<>();
        final Map<String, List<RemoteTestRunDto>> testRunsByShaCache = new ConcurrentHashMap<>();
        final Map<String, Optional<RemoteRequirementTestTraceDto>> testTraceByReqKeyCache = new ConcurrentHashMap<>();
        final Map<String, List<RemoteAiAnalysisDto>> analysesByPrKeyCache = new ConcurrentHashMap<>();
        final Map<UUID, List<RemoteEngineeringActionDto>> actionsByAnalysisIdCache = new ConcurrentHashMap<>();
    }

    @Transactional(readOnly = true)
    public ProjectTraceabilityResponse getProjectTraceability(UUID organizationId, UUID projectId) {
        return getProjectTraceability(organizationId, projectId, null);
    }

    @Transactional(readOnly = true)
    public ProjectTraceabilityResponse getProjectTraceability(UUID organizationId, UUID projectId, UUID userId) {
        validateTenant(organizationId);
        Project project = resolveProject(organizationId, projectId, userId);

        UUID wsId = project.getWorkspaceId();
        UUID effectiveOrgId = project.getOrganizationId();
        RequestAggregationCache cache = new RequestAggregationCache();

        // 1. Fetch domain items
        List<Requirement> requirements = effectiveOrgId != null
                ? requirementRepository.findAllByOrganizationIdAndProjectId(effectiveOrgId, projectId)
                : requirementRepository.findAllByProjectId(projectId);
        List<Task> tasks = effectiveOrgId != null
                ? taskRepository.findAllByOrganizationIdAndProjectId(effectiveOrgId, projectId)
                : taskRepository.findAllByProjectId(projectId);
        List<ProjectRepositoryLink> repoLinks = effectiveOrgId != null
                ? projectRepositoryLinkRepository.findByProjectId(effectiveOrgId, projectId)
                : projectRepositoryLinkRepository.findByProjectId(projectId);
        List<TraceabilityLink> persistedLinks = effectiveOrgId != null
                ? traceabilityLinkRepository.findByProjectId(effectiveOrgId, projectId)
                : traceabilityLinkRepository.findByProjectId(projectId);

        // Map tasks by requirement ID
        Map<UUID, List<Task>> tasksByReqId = tasks.stream()
                .filter(t -> t.getRequirementId() != null)
                .collect(Collectors.groupingBy(Task::getRequirementId));

        // Fetch task code links
        Map<UUID, List<CodeLink>> codeLinksByTaskId = new HashMap<>();
        for (Task t : tasks) {
            codeLinksByTaskId.put(t.getId(), effectiveOrgId != null
                    ? codeLinkRepository.findAllByOrganizationIdAndTaskId(effectiveOrgId, t.getId())
                    : codeLinkRepository.findAllByTaskId(t.getId()));
        }

        // 2. Fetch external artifacts across connected repositories (using cache)
        List<RemotePullRequestDto> allPrs = new ArrayList<>();
        List<RemoteCommitDto> allCommits = new ArrayList<>();
        Map<UUID, RemoteRepositoryDto> repoDetails = new HashMap<>();
        boolean downstreamAvailable = true;

        for (ProjectRepositoryLink rl : repoLinks) {
            Optional<RemoteRepositoryDto> repoOpt = cache.repoCache.computeIfAbsent(
                    rl.getRepositoryId(),
                    rId -> effectiveOrgId != null
                            ? sourceControlClient.getRepository(effectiveOrgId, rId)
                            : sourceControlClient.getRepository(wsId, null, rId)
            );
            repoOpt.ifPresent(r -> repoDetails.put(rl.getRepositoryId(), r));

            List<RemotePullRequestDto> prs = cache.prsByRepoCache.computeIfAbsent(
                    rl.getRepositoryId(),
                    rId -> {
                        try {
                            return effectiveOrgId != null
                                    ? sourceControlClient.listPullRequests(effectiveOrgId, rId)
                                    : sourceControlClient.listPullRequests(wsId, null, rId);
                        } catch (Exception e) {
                            log.warn("Failed to fetch PRs for repo {}: {}", rId, e.getMessage());
                            return Collections.emptyList();
                        }
                    }
            );
            allPrs.addAll(prs);

            List<RemoteCommitDto> commits = cache.commitsByRepoCache.computeIfAbsent(
                    rl.getRepositoryId(),
                    rId -> {
                        try {
                            return effectiveOrgId != null
                                    ? sourceControlClient.listCommits(effectiveOrgId, rId)
                                    : sourceControlClient.listCommits(wsId, null, rId);
                        } catch (Exception e) {
                            log.warn("Failed to fetch commits for repo {}: {}", rId, e.getMessage());
                            return Collections.emptyList();
                        }
                    }
            );
            allCommits.addAll(commits);
        }

        // 3. Fetch test traces for requirements and PR test runs
        Map<String, RemoteRequirementTestTraceDto> reqTestsByKey = new HashMap<>();
        for (Requirement r : requirements) {
            Optional<RemoteRequirementTestTraceDto> traceOpt = cache.testTraceByReqKeyCache.computeIfAbsent(
                    r.getIdentifier(),
                    rKey -> effectiveOrgId != null
                            ? testingServiceClient.getTraceabilityForRequirement(effectiveOrgId, rKey)
                            : testingServiceClient.getTraceabilityForRequirement(wsId, null, rKey)
            );
            traceOpt.ifPresent(t -> reqTestsByKey.put(r.getIdentifier(), t));
        }

        Map<String, List<RemoteTestRunDto>> testRunsBySha = new HashMap<>();
        for (RemotePullRequestDto pr : allPrs) {
            if (pr.getHeadSha() != null) {
                List<RemoteTestRunDto> runs = cache.testRunsByShaCache.computeIfAbsent(
                        pr.getHeadSha(),
                        sha -> effectiveOrgId != null
                                ? testingServiceClient.listTestRuns(effectiveOrgId, pr.getRepositoryId(), sha)
                                : testingServiceClient.listTestRuns(wsId, null, pr.getRepositoryId(), sha)
                );
                testRunsBySha.put(pr.getHeadSha(), runs);
            }
        }

        // 4. Fetch AI analyses, risk evaluations, and remediation actions for PRs
        Map<String, List<RemoteAiAnalysisDto>> aiAnalysesByPrKey = new HashMap<>();
        Map<UUID, List<RemoteEngineeringActionDto>> actionsByAnalysisId = new HashMap<>();
        List<RemoteAiAnalysisDto> allAnalyses = new ArrayList<>();

        for (RemotePullRequestDto pr : allPrs) {
            if (pr == null || pr.getPrNumber() == null) {
                continue;
            }
            Integer prNum = pr.getPrNumber();
            String prKey = pr.getRepositoryId() + "#" + prNum;
            List<RemoteAiAnalysisDto> analyses = cache.analysesByPrKeyCache.computeIfAbsent(
                    prKey,
                    k -> effectiveOrgId != null
                            ? aiServiceClient.getAnalysesForPullRequest(effectiveOrgId, pr.getRepositoryId(), prNum)
                            : aiServiceClient.getAnalysesForPullRequest(wsId, null, pr.getRepositoryId(), prNum)
            );
            aiAnalysesByPrKey.put(prKey, analyses);
            allAnalyses.addAll(analyses);

            for (RemoteAiAnalysisDto analysis : analyses) {
                if (analysis.getId() != null) {
                    List<RemoteEngineeringActionDto> actions = cache.actionsByAnalysisIdCache.computeIfAbsent(
                            analysis.getId(),
                            aId -> effectiveOrgId != null
                                    ? aiServiceClient.getEngineeringActions(effectiveOrgId, aId)
                                    : aiServiceClient.getEngineeringActions(wsId, null, aId)
                    );
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
                if (!commitMatches) {
                    for (Task t : reqTasks) {
                        List<CodeLink> clList = codeLinksByTaskId.getOrDefault(t.getId(), Collections.emptyList());
                        for (CodeLink cl : clList) {
                            if (cl.getLinkType() == CodeLinkType.COMMIT && cl.getExternalRef().equalsIgnoreCase(commit.getCommitSha())) {
                                commitMatches = true;
                                break;
                            }
                        }
                        if (commitMatches) break;
                    }
                }
                if (commitMatches) {
                    chainCommits.add(commit);
                    if (testRunsBySha.containsKey(commit.getCommitSha())) {
                        chainTestRuns.addAll(testRunsBySha.get(commit.getCommitSha()));
                    }
                }
            }

            // Compute requirement health and gaps
            RequirementHealth reqHealth = computeRequirementHealth(
                    req, reqTasks, chainCommits, chainPrs, chainTestRuns, chainTestCases, chainAnalyses, chainActions
            );
            List<TraceabilityGap> reqGaps = detectRequirementGaps(
                    req, reqTasks, chainCommits, chainPrs, chainTestRuns, chainTestCases, chainAnalyses, chainActions
            );
            allGaps.addAll(reqGaps);

            chains.add(new TraceabilitySpineChainDto(
                    RequirementResponse.from(req),
                    reqTasks.stream().map(TaskResponse::from).collect(Collectors.toList()),
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

        // 6. Detect project-level gaps (including CODE_WITHOUT_PR and PR_WITHOUT_TASK)
        List<TraceabilityGap> projectGaps = detectProjectGaps(tasks, allPrs, allCommits, codeLinksByTaskId, chains);
        allGaps.addAll(projectGaps);

        // 7. Compute deterministic ProjectHealth from authoritative downstream intelligence
        ProjectHealth projectHealth = computeProjectHealth(requirements, tasks, chains, allGaps, allPrs, allAnalyses, testRunsBySha);

        // 8. Map repositories and links
        List<ProjectRepositoryLinkDto> repoDtos = repoLinks.stream()
                .map(ProjectRepositoryLinkDto::from)
                .collect(Collectors.toList());

        List<TraceabilityLinkDto> linkDtos = persistedLinks.stream()
                .map(TraceabilityLinkDto::from)
                .collect(Collectors.toList());

        return new ProjectTraceabilityResponse(
                projectId,
                effectiveOrgId,
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
        return getProjectHealth(organizationId, projectId, null);
    }

    @Transactional(readOnly = true)
    public ProjectHealth getProjectHealth(UUID organizationId, UUID projectId, UUID userId) {
        return getProjectTraceability(organizationId, projectId, userId).health();
    }

    @Transactional(readOnly = true)
    public List<TraceabilityGap> getProjectGaps(UUID organizationId, UUID projectId) {
        return getProjectGaps(organizationId, projectId, null);
    }

    @Transactional(readOnly = true)
    public List<TraceabilityGap> getProjectGaps(UUID organizationId, UUID projectId, UUID userId) {
        return getProjectTraceability(organizationId, projectId, userId).gaps();
    }

    @Transactional(readOnly = true)
    public TraceabilitySpineChainDto getRequirementTraceability(UUID organizationId, UUID requirementId) {
        return getRequirementTraceability(organizationId, requirementId, null);
    }

    @Transactional(readOnly = true)
    public TraceabilitySpineChainDto getRequirementTraceability(UUID organizationId, UUID requirementId, UUID userId) {
        validateTenant(organizationId);
        Requirement req = requirementRepository.findById(requirementId)
                .orElseThrow(() -> new ResourceNotFoundException("Requirement not found: id=" + requirementId));
        resolveProject(organizationId, req.getProjectId(), userId);

        ProjectTraceabilityResponse full = getProjectTraceability(organizationId, req.getProjectId(), userId);
        return full.chains().stream()
                .filter(c -> c.requirement().id().equals(requirementId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Traceability chain not found for requirement: " + requirementId));
    }

    @Transactional(readOnly = true)
    public TaskTraceabilityResponse getTaskTraceability(UUID organizationId, UUID taskId) {
        return getTaskTraceability(organizationId, taskId, null);
    }

    @Transactional(readOnly = true)
    public TaskTraceabilityResponse getTaskTraceability(UUID organizationId, UUID taskId, UUID userId) {
        validateTenant(organizationId);
        if (taskId == null) {
            throw new IllegalArgumentException("taskId cannot be null");
        }

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: id=" + taskId));
        resolveProject(organizationId, task.getProjectId(), userId);

        ProjectTraceabilityResponse full = getProjectTraceability(organizationId, task.getProjectId(), userId);

        // Find parent requirement and chain
        RequirementResponse parentReq = null;
        TraceabilitySpineChainDto parentChain = null;
        if (task.getRequirementId() != null) {
            parentChain = full.chains().stream()
                    .filter(c -> c.requirement() != null && task.getRequirementId().equals(c.requirement().id()))
                    .findFirst()
                    .orElse(null);
            if (parentChain != null) {
                parentReq = parentChain.requirement();
            }
        }

        // Explicit Code Links
        List<CodeLinkResponse> codeLinks = (organizationId != null
                ? codeLinkRepository.findAllByOrganizationIdAndTaskId(organizationId, task.getId())
                : codeLinkRepository.findAllByTaskId(task.getId())).stream()
                .map(CodeLinkResponse::from)
                .collect(Collectors.toList());

        // Traceability links relating to this task
        String taskIdStr = task.getId().toString();
        String taskKeyLower = task.getTaskKey() != null ? task.getTaskKey().toLowerCase() : "";
        List<TraceabilityLinkDto> taskLinks = full.links().stream()
                .filter(l -> taskIdStr.equals(l.sourceId()) || taskIdStr.equals(l.targetId()) ||
                        (l.externalReference() != null && (!taskKeyLower.isEmpty() && matchesText(l.externalReference(), taskKeyLower) ||
                                codeLinks.stream().anyMatch(cl -> cl.externalRef().equalsIgnoreCase(l.externalReference())))))
                .collect(Collectors.toList());

        // Related Commits
        Set<String> commitShas = new HashSet<>();
        for (CodeLinkResponse cl : codeLinks) {
            if (cl.linkType() == CodeLinkType.COMMIT && cl.externalRef() != null) {
                commitShas.add(cl.externalRef().toLowerCase());
            }
        }
        for (TraceabilityLinkDto l : taskLinks) {
            if ("COMMIT".equalsIgnoreCase(l.targetType()) && l.targetId() != null) {
                commitShas.add(l.targetId().toLowerCase());
            }
        }

        List<RemoteCommitDto> relatedCommits = new ArrayList<>();
        if (parentChain != null && parentChain.commits() != null) {
            for (RemoteCommitDto c : parentChain.commits()) {
                if (c.getCommitSha() != null && (commitShas.contains(c.getCommitSha().toLowerCase()) ||
                        (!taskKeyLower.isEmpty() && matchesText(c.getMessage(), taskKeyLower)))) {
                    relatedCommits.add(c);
                }
            }
        }

        // Related Pull Requests
        Set<Integer> prNumbers = new HashSet<>();
        for (CodeLinkResponse cl : codeLinks) {
            if (cl.linkType() == CodeLinkType.PULL_REQUEST && cl.externalRef() != null) {
                try {
                    prNumbers.add(Integer.parseInt(cl.externalRef()));
                } catch (NumberFormatException ignored) {}
            }
        }
        for (TraceabilityLinkDto l : taskLinks) {
            if ("PULL_REQUEST".equalsIgnoreCase(l.targetType()) && l.targetId() != null) {
                try {
                    prNumbers.add(Integer.parseInt(l.targetId()));
                } catch (NumberFormatException ignored) {}
            }
        }

        List<RemotePullRequestDto> relatedPrs = new ArrayList<>();
        if (parentChain != null && parentChain.pullRequests() != null) {
            for (RemotePullRequestDto pr : parentChain.pullRequests()) {
                if (pr.getPrNumber() != null && (prNumbers.contains(pr.getPrNumber()) ||
                        (!taskKeyLower.isEmpty() && (matchesText(pr.getTitle(), taskKeyLower) ||
                                matchesText(pr.getBody(), taskKeyLower) ||
                                matchesText(pr.getHeadBranch(), taskKeyLower))))) {
                    relatedPrs.add(pr);
                }
            }
        }

        // Test runs for related PRs / commits
        Set<String> prHeadShas = relatedPrs.stream()
                .map(RemotePullRequestDto::getHeadSha)
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        List<RemoteTestRunDto> relatedTestRuns = new ArrayList<>();
        if (parentChain != null && parentChain.testRuns() != null) {
            for (RemoteTestRunDto tr : parentChain.testRuns()) {
                if (parentChain.testRuns().size() <= 2 ||
                        (tr.getId() != null && commitShas.stream().anyMatch(sha -> sha.startsWith(tr.getId().toString().toLowerCase())))) {
                    relatedTestRuns.add(tr);
                }
            }
        }

        // Test cases from parent requirement
        List<RemoteRequirementTestTraceDto.RemoteTestCaseTraceSummary> testCases =
                parentChain != null && parentChain.testCases() != null ? parentChain.testCases() : Collections.emptyList();

        // AI Analyses for related PRs
        List<RemoteAiAnalysisDto> relatedAnalyses = new ArrayList<>();
        if (parentChain != null && parentChain.aiAnalyses() != null) {
            for (RemotePullRequestDto pr : relatedPrs) {
                String prKey = pr.getRepositoryId() + "#" + pr.getPrNumber();
                for (RemoteAiAnalysisDto a : parentChain.aiAnalyses()) {
                    if (prKey.equals(a.getEntityId())) {
                        relatedAnalyses.add(a);
                    }
                }
            }
        }

        // Remediation actions for related analyses
        Set<UUID> analysisIds = relatedAnalyses.stream()
                .map(RemoteAiAnalysisDto::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<RemoteEngineeringActionDto> relatedActions = parentChain != null && parentChain.remediationActions() != null
                ? parentChain.remediationActions().stream()
                        .filter(act -> act.getAnalysisId() != null && analysisIds.contains(act.getAnalysisId()))
                        .collect(Collectors.toList())
                : Collections.emptyList();

        // Gaps applicable to this task
        List<TraceabilityGap> applicableGaps = full.gaps().stream()
                .filter(g -> taskIdStr.equals(g.getArtifactId()) ||
                        (g.getArtifactId() != null && g.getArtifactId().startsWith(taskIdStr)) ||
                        (!taskKeyLower.isEmpty() && task.getTaskKey().equalsIgnoreCase(g.getArtifactName())) ||
                        (!taskKeyLower.isEmpty() && g.getArtifactName() != null && g.getArtifactName().toLowerCase().contains(taskKeyLower)) ||
                        (task.getRequirementId() != null && task.getRequirementId().toString().equals(g.getArtifactId())))
                .collect(Collectors.toList());

        String evidenceStatus = full.health() != null ? full.health().getEvidenceStatus() : "AVAILABLE";
        RequirementHealth health = parentChain != null ? parentChain.health() : null;

        return new TaskTraceabilityResponse(
                task.getId(),
                organizationId,
                task.getProjectId(),
                TaskResponse.from(task),
                parentReq,
                codeLinks,
                relatedCommits,
                relatedPrs,
                relatedTestRuns,
                testCases,
                relatedAnalyses,
                relatedActions,
                applicableGaps,
                taskLinks,
                evidenceStatus,
                health,
                Instant.now()
        );
    }

    @Transactional
    public int syncTraceability(UUID organizationId, UUID projectId) {
        return syncTraceability(organizationId, projectId, null);
    }

    @Transactional
    public int syncTraceability(UUID organizationId, UUID projectId, UUID userId) {
        validateTenant(organizationId);
        Project project = resolveProject(organizationId, projectId, userId);

        UUID wsId = project.getWorkspaceId();
        UUID effectiveOrgId = project.getOrganizationId();

        List<Requirement> requirements = effectiveOrgId != null
                ? requirementRepository.findAllByOrganizationIdAndProjectId(effectiveOrgId, projectId)
                : requirementRepository.findAllByProjectId(projectId);
        List<Task> tasks = effectiveOrgId != null
                ? taskRepository.findAllByOrganizationIdAndProjectId(effectiveOrgId, projectId)
                : taskRepository.findAllByProjectId(projectId);
        List<ProjectRepositoryLink> repoLinks = effectiveOrgId != null
                ? projectRepositoryLinkRepository.findByProjectId(effectiveOrgId, projectId)
                : projectRepositoryLinkRepository.findByProjectId(projectId);

        int createdCount = 0;

        // 1. Requirement -> Task linkage via task.requirement_id
        for (Task task : tasks) {
            if (task.getRequirementId() != null) {
                boolean exists = traceabilityLinkRepository.exists(
                        wsId, effectiveOrgId, projectId,
                        TraceabilityType.REQUIREMENT, task.getRequirementId().toString(),
                        TraceabilityType.TASK, task.getId().toString(),
                        LinkOrigin.REQUIREMENT_TASK_RELATION.name()
                );
                if (!exists) {
                    TraceabilityLink link = TraceabilityLink.builder()
                            .id(UUID.randomUUID())
                            .workspaceId(wsId)
                            .organizationId(effectiveOrgId)
                            .projectId(projectId)
                            .sourceType(TraceabilityType.REQUIREMENT)
                            .sourceId(task.getRequirementId().toString())
                            .targetType(TraceabilityType.TASK)
                            .targetId(task.getId().toString())
                            .linkOrigin(LinkOrigin.REQUIREMENT_TASK_RELATION)
                            .confidence(ConfidenceLevel.HIGH)
                            .evidence("task_requirement_fk")
                            .active(true)
                            .createdAt(Instant.now())
                            .build();
                    traceabilityLinkRepository.save(link);
                    createdCount++;
                }
            }

            // 2. Explicit Task Code Links
            List<CodeLink> codeLinks = effectiveOrgId != null
                    ? codeLinkRepository.findAllByOrganizationIdAndTaskId(effectiveOrgId, task.getId())
                    : codeLinkRepository.findAllByTaskId(task.getId());
            for (CodeLink cl : codeLinks) {
                TraceabilityType targetType = switch (cl.getLinkType()) {
                    case PULL_REQUEST -> TraceabilityType.PULL_REQUEST;
                    case COMMIT -> TraceabilityType.COMMIT;
                    default -> TraceabilityType.CODE;
                };
                boolean exists = traceabilityLinkRepository.exists(
                        wsId, effectiveOrgId, projectId,
                        TraceabilityType.TASK, task.getId().toString(),
                        targetType, cl.getExternalRef(),
                        LinkOrigin.EXPLICIT_USER_LINK.name()
                );
                if (!exists) {
                    TraceabilityLink link = TraceabilityLink.builder()
                            .id(UUID.randomUUID())
                            .workspaceId(wsId)
                            .organizationId(effectiveOrgId)
                            .projectId(projectId)
                            .sourceType(TraceabilityType.TASK)
                            .sourceId(task.getId().toString())
                            .targetType(targetType)
                            .targetId(cl.getExternalRef())
                            .externalReference(cl.getExternalRef())
                            .linkOrigin(LinkOrigin.EXPLICIT_USER_LINK)
                            .confidence(ConfidenceLevel.HIGH)
                            .evidence("explicit_task_code_link=" + cl.getId())
                            .active(true)
                            .createdAt(Instant.now())
                            .build();
                    traceabilityLinkRepository.save(link);
                    createdCount++;
                }
            }
        }

        // 3. Derived Task -> PR and Task -> Commit linkages from GitHub activity
        for (ProjectRepositoryLink repoLink : repoLinks) {
            List<RemotePullRequestDto> prs = effectiveOrgId != null
                    ? sourceControlClient.listPullRequests(effectiveOrgId, repoLink.getRepositoryId())
                    : sourceControlClient.listPullRequests(wsId, null, repoLink.getRepositoryId());
            for (RemotePullRequestDto pr : prs) {
                for (Task task : tasks) {
                    String taskKey = task.getTaskKey().toLowerCase();
                    boolean titleMatches = matchesText(pr.getTitle(), taskKey);
                    boolean bodyMatches = matchesText(pr.getBody(), taskKey);
                    boolean branchMatches = matchesText(pr.getHeadBranch(), taskKey);

                    if (titleMatches || bodyMatches || branchMatches) {
                        LinkOrigin origin = branchMatches && !titleMatches && !bodyMatches
                                ? LinkOrigin.BRANCH_REFERENCE
                                : LinkOrigin.PR_REFERENCE;
                        String evidence = branchMatches && !titleMatches && !bodyMatches
                                ? "head_branch=" + pr.getHeadBranch()
                                : "pr_text_match=" + pr.getPrNumber();

                        boolean exists = traceabilityLinkRepository.exists(
                                wsId, effectiveOrgId, projectId,
                                TraceabilityType.TASK, task.getId().toString(),
                                TraceabilityType.PULL_REQUEST, String.valueOf(pr.getPrNumber()),
                                origin.name()
                        );
                        if (!exists) {
                            TraceabilityLink link = TraceabilityLink.builder()
                                    .id(UUID.randomUUID())
                                    .workspaceId(wsId)
                                    .organizationId(effectiveOrgId)
                                    .projectId(projectId)
                                    .sourceType(TraceabilityType.TASK)
                                    .sourceId(task.getId().toString())
                                    .targetType(TraceabilityType.PULL_REQUEST)
                                    .targetId(String.valueOf(pr.getPrNumber()))
                                    .repositoryId(pr.getRepositoryId())
                                    .externalReference("PR #" + pr.getPrNumber())
                                    .linkOrigin(origin)
                                    .confidence(ConfidenceLevel.HIGH)
                                    .evidence(evidence)
                                    .active(true)
                                    .createdAt(Instant.now())
                                    .build();
                            traceabilityLinkRepository.save(link);
                            createdCount++;
                        }
                    }
                }
            }

            List<RemoteCommitDto> commits = effectiveOrgId != null
                    ? sourceControlClient.listCommits(effectiveOrgId, repoLink.getRepositoryId())
                    : sourceControlClient.listCommits(wsId, null, repoLink.getRepositoryId());
            for (RemoteCommitDto commit : commits) {
                for (Task task : tasks) {
                    if (matchesText(commit.getMessage(), task.getTaskKey().toLowerCase())) {
                        boolean exists = traceabilityLinkRepository.exists(
                                wsId, effectiveOrgId, projectId,
                                TraceabilityType.TASK, task.getId().toString(),
                                TraceabilityType.COMMIT, commit.getCommitSha(),
                                LinkOrigin.COMMIT_MESSAGE.name()
                        );
                        if (!exists) {
                            TraceabilityLink link = TraceabilityLink.builder()
                                    .id(UUID.randomUUID())
                                    .workspaceId(wsId)
                                    .organizationId(effectiveOrgId)
                                    .projectId(projectId)
                                    .sourceType(TraceabilityType.TASK)
                                    .sourceId(task.getId().toString())
                                    .targetType(TraceabilityType.COMMIT)
                                    .targetId(commit.getCommitSha())
                                    .repositoryId(repoLink.getRepositoryId())
                                    .externalReference(commit.getCommitSha())
                                    .linkOrigin(LinkOrigin.COMMIT_MESSAGE)
                                    .confidence(ConfidenceLevel.HIGH)
                                    .evidence("commit_message_match=" + commit.getCommitSha().substring(0, Math.min(7, commit.getCommitSha().length())))
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

        if (projectMetrics != null) {
            projectMetrics.recordTraceabilitySync();
        }
        log.info("Traceability sync completed for project {}: {} new links registered", projectId, createdCount);
        if (structuredLogger != null) {
            structuredLogger.info("traceability.sync.completed", Map.of(
                    "projectId", projectId.toString(),
                    "syncedLinks", createdCount
            ));
        }
        return createdCount;
    }

    @Transactional
    public TraceabilityLink createLink(UUID organizationId, UUID projectId, CreateTraceabilityLinkRequest request) {
        return createLink(organizationId, projectId, null, request);
    }

    @Transactional
    public TraceabilityLink createLink(UUID organizationId, UUID projectId, UUID userId, CreateTraceabilityLinkRequest request) {
        validateTenant(organizationId);
        Project project = resolveProject(organizationId, projectId, userId);

        TraceabilityLink link = TraceabilityLink.builder()
                .id(UUID.randomUUID())
                .workspaceId(project.getWorkspaceId())
                .organizationId(project.getOrganizationId())
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

        TraceabilityLink saved = traceabilityLinkRepository.save(link);
        if (auditService != null) {
            UUID actor = userId != null ? userId : com.forgeai.project.infrastructure.security.TenantContext.getUserId();
            String actorStr = actor != null ? actor.toString() : "system";
            auditService.recordEvent(
                    organizationId,
                    actorStr,
                    "TRACEABILITY_LINK_CREATED",
                    "TRACEABILITY_LINK",
                    saved.getId().toString(),
                    "CREATE",
                    "SUCCESS",
                    Map.of("sourceType", request.sourceType(), "targetType", request.targetType(), "projectId", projectId.toString())
            );
        }
        return saved;
    }

    @Transactional
    public void deleteLink(UUID organizationId, UUID linkId) {
        deleteLink(organizationId, linkId, null);
    }

    @Transactional
    public void deleteLink(UUID organizationId, UUID linkId, UUID userId) {
        validateTenant(organizationId);
        TraceabilityLink link = traceabilityLinkRepository.findById(linkId)
                .orElseThrow(() -> new ResourceNotFoundException("Traceability link not found: " + linkId));
        Project project = resolveProject(organizationId, link.getProjectId(), userId);
        UUID effectiveOrgId = project.getOrganizationId();
        if (effectiveOrgId != null) {
            traceabilityLinkRepository.deleteById(effectiveOrgId, linkId);
        } else {
            traceabilityLinkRepository.deleteById(project.getWorkspaceId(), null, linkId);
        }
        if (auditService != null) {
            UUID actor = userId != null ? userId : com.forgeai.project.infrastructure.security.TenantContext.getUserId();
            String actorStr = actor != null ? actor.toString() : "system";
            auditService.recordEvent(
                    organizationId,
                    actorStr,
                    "TRACEABILITY_LINK_DELETED",
                    "TRACEABILITY_LINK",
                    linkId.toString(),
                    "DELETE",
                    "SUCCESS",
                    null
            );
        }
    }

    @Transactional
    public ProjectRepositoryLink linkRepository(UUID organizationId, UUID projectId, UUID repositoryId, String repositoryName) {
        return linkRepository(organizationId, projectId, null, repositoryId, repositoryName);
    }

    @Transactional
    public ProjectRepositoryLink linkRepository(UUID organizationId, UUID projectId, UUID userId, UUID repositoryId, String repositoryName) {
        Project project = resolveProject(organizationId, projectId, userId);
        UUID effectiveOrgId = project.getOrganizationId();

        // 2. Prevent duplicate repository bindings & support idempotent re-link
        boolean alreadyLinked = effectiveOrgId != null
                ? projectRepositoryLinkRepository.existsByProjectIdAndRepositoryId(effectiveOrgId, projectId, repositoryId)
                : projectRepositoryLinkRepository.existsByProjectIdAndRepositoryId(projectId, repositoryId);
        if (alreadyLinked) {
            List<ProjectRepositoryLink> existingLinks = effectiveOrgId != null
                    ? projectRepositoryLinkRepository.findByProjectId(effectiveOrgId, projectId)
                    : projectRepositoryLinkRepository.findByProjectId(projectId);
            for (ProjectRepositoryLink existingLink : existingLinks) {
                if (repositoryId.equals(existingLink.getRepositoryId())) {
                    log.info("Repository {} is already linked to project {}; returning existing link idempotently",
                            repositoryId, projectId);
                    return existingLink;
                }
            }
        }

        // 3. Resolve repository metadata with fast-path fallback
        String authoritativeName;
        if (repositoryName != null && !repositoryName.isBlank()) {
            authoritativeName = repositoryName.trim();
        } else {
            Optional<RemoteRepositoryDto> repoDtoOpt = effectiveOrgId != null
                    ? sourceControlClient.getRepository(effectiveOrgId, repositoryId)
                    : sourceControlClient.getRepository(project.getWorkspaceId(), null, repositoryId);

            if (repoDtoOpt.isPresent()) {
                RemoteRepositoryDto repoDto = repoDtoOpt.get();
                authoritativeName = repoDto.getFullName() != null && !repoDto.getFullName().isBlank()
                        ? repoDto.getFullName()
                        : (repoDto.getName() != null ? repoDto.getName() : "repository-" + repositoryId.toString().substring(0, 8));
            } else {
                authoritativeName = "repository-" + repositoryId.toString().substring(0, 8);
            }
        }

        ProjectRepositoryLink link = ProjectRepositoryLink.builder()
                .id(UUID.randomUUID())
                .workspaceId(project.getWorkspaceId())
                .organizationId(project.getOrganizationId())
                .projectId(projectId)
                .repositoryId(repositoryId)
                .repositoryName(authoritativeName)
                .createdAt(Instant.now())
                .build();

        return projectRepositoryLinkRepository.save(link);
    }

    @Transactional
    public void unlinkRepository(UUID organizationId, UUID projectId, UUID repositoryId) {
        unlinkRepository(organizationId, projectId, null, repositoryId);
    }

    @Transactional
    public void unlinkRepository(UUID organizationId, UUID projectId, UUID userId, UUID repositoryId) {
        Project project = resolveProject(organizationId, projectId, userId);
        UUID effectiveOrgId = project.getOrganizationId();
        if (effectiveOrgId != null) {
            projectRepositoryLinkRepository.deleteByProjectIdAndRepositoryId(effectiveOrgId, projectId, repositoryId);
        } else {
            projectRepositoryLinkRepository.deleteByProjectIdAndRepositoryId(projectId, repositoryId);
        }
    }

    @Transactional(readOnly = true)
    public List<ProjectRepositoryLinkDto> listProjectRepositories(UUID organizationId, UUID projectId) {
        return listProjectRepositories(organizationId, projectId, null);
    }

    @Transactional(readOnly = true)
    public List<ProjectRepositoryLinkDto> listProjectRepositories(UUID organizationId, UUID projectId, UUID userId) {
        Project project = resolveProject(organizationId, projectId, userId);
        UUID effectiveOrgId = project.getOrganizationId();
        if (effectiveOrgId != null) {
            return projectRepositoryLinkRepository.findByProjectId(effectiveOrgId, projectId).stream()
                    .map(ProjectRepositoryLinkDto::from)
                    .collect(Collectors.toList());
        }

        return projectRepositoryLinkRepository.findByProjectId(projectId).stream()
                .map(ProjectRepositoryLinkDto::from)
                .collect(Collectors.toList());
    }

    public Project resolveProject(UUID organizationId, UUID projectId, UUID userId) {
        if (projectId == null) {
            throw new IllegalArgumentException("Project ID is required");
        }
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: id=" + projectId));

        if (project.getOrganizationId() != null) {
            if (organizationId != null && !organizationId.equals(project.getOrganizationId())) {
                throw new ResourceNotFoundException("Project not found: id=" + projectId + " in org=" + organizationId);
            }
        } else {
            verifyPersonalProjectOwnership(project, userId != null ? userId : com.forgeai.project.infrastructure.security.TenantContext.getUserId());
        }
        return project;
    }

    private void verifyPersonalProjectOwnership(Project project, UUID userId) {
        if (project.getOrganizationId() == null) {
            WorkspaceJpaEntity ws = workspaceRepository.findById(project.getWorkspaceId())
                    .orElseThrow(() -> new ResourceNotFoundException("Workspace not found: id=" + project.getWorkspaceId()));
            if (userId == null || !userId.equals(ws.getOwnerUserId())) {
                throw new AccessDeniedException("Access denied to personal project: " + project.getId());
            }
        }
    }

    @Transactional
    public TraceabilitySnapshot createSnapshot(UUID organizationId, UUID projectId, String snapshotName, Integer prNumber, String commitSha, UUID repositoryId) {
        return createSnapshot(organizationId, projectId, null, snapshotName, prNumber, commitSha, repositoryId);
    }

    @Transactional
    public TraceabilitySnapshot createSnapshot(UUID organizationId, UUID projectId, UUID userId, String snapshotName, Integer prNumber, String commitSha, UUID repositoryId) {
        validateTenant(organizationId);
        Project project = resolveProject(organizationId, projectId, userId);

        ProjectTraceabilityResponse response = getProjectTraceability(organizationId, projectId, userId);

        List<TraceabilitySnapshot> existing = organizationId != null
                ? traceabilitySnapshotRepository.findByProjectId(organizationId, projectId)
                : traceabilitySnapshotRepository.findByProjectId(projectId);
        int nextVersion = existing.size() + 1;
        String name = (snapshotName != null && !snapshotName.isBlank()) ? snapshotName : "Snapshot v" + nextVersion;

        String dataJson;
        try {
            dataJson = objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            dataJson = "{}";
        }

        UUID actor = userId != null ? userId : com.forgeai.project.infrastructure.security.TenantContext.getUserId();
        String actorStr = actor != null ? actor.toString() : "system";
        TraceabilitySnapshot snapshot = TraceabilitySnapshot.builder()
                .id(UUID.randomUUID())
                .workspaceId(project.getWorkspaceId())
                .organizationId(project.getOrganizationId())
                .projectId(projectId)
                .snapshotName(name)
                .prNumber(prNumber)
                .commitSha(commitSha)
                .repositoryId(repositoryId)
                .createdBy(actorStr)
                .schemaVersion("1.0")
                .snapshotData(dataJson)
                .createdAt(Instant.now())
                .build();

        TraceabilitySnapshot saved = traceabilitySnapshotRepository.save(snapshot);
        if (auditService != null) {
            auditService.recordEvent(
                    organizationId,
                    actorStr,
                    "TRACEABILITY_SNAPSHOT_CREATED",
                    "TRACEABILITY_SNAPSHOT",
                    saved.getId().toString(),
                    "CREATE",
                    "SUCCESS",
                    Map.of("snapshotName", name, "projectId", projectId.toString())
            );
        }
        if (structuredLogger != null) {
            structuredLogger.info("traceability.snapshot.created", Map.of(
                    "snapshotId", saved.getId().toString(),
                    "projectId", projectId.toString(),
                    "snapshotName", name
            ));
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<TraceabilitySnapshotResponse> listSnapshots(UUID organizationId, UUID projectId) {
        return listSnapshots(organizationId, projectId, null);
    }

    @Transactional(readOnly = true)
    public List<TraceabilitySnapshotResponse> listSnapshots(UUID organizationId, UUID projectId, UUID userId) {
        validateTenant(organizationId);
        Project project = resolveProject(organizationId, projectId, userId);
        UUID effectiveOrgId = project.getOrganizationId();
        if (effectiveOrgId != null) {
            return traceabilitySnapshotRepository.findByProjectId(effectiveOrgId, projectId).stream()
                    .map(TraceabilitySnapshotResponse::from)
                    .collect(Collectors.toList());
        }
        return traceabilitySnapshotRepository.findByProjectId(projectId).stream()
                .map(TraceabilitySnapshotResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TraceabilitySnapshotResponse getSnapshot(UUID organizationId, UUID snapshotId) {
        return getSnapshot(organizationId, null, snapshotId, null);
    }

    @Transactional(readOnly = true)
    public TraceabilitySnapshotResponse getSnapshot(UUID organizationId, UUID snapshotId, UUID userId) {
        return getSnapshot(organizationId, null, snapshotId, userId);
    }

    @Transactional(readOnly = true)
    public TraceabilitySnapshotResponse getSnapshot(UUID organizationId, UUID projectId, UUID snapshotId, UUID userId) {
        validateTenant(organizationId);
        TraceabilitySnapshot snapshot;
        if (organizationId != null) {
            snapshot = traceabilitySnapshotRepository.findById(organizationId, snapshotId)
                    .orElseThrow(() -> new ResourceNotFoundException("Snapshot not found: id=" + snapshotId));
        } else {
            snapshot = traceabilitySnapshotRepository.findById(snapshotId)
                    .orElseThrow(() -> new ResourceNotFoundException("Snapshot not found: id=" + snapshotId));
            Project project = projectRepository.findById(snapshot.getProjectId())
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found: id=" + snapshot.getProjectId()));
            verifyPersonalProjectOwnership(project, userId != null ? userId : com.forgeai.project.infrastructure.security.TenantContext.getUserId());
        }
        if (projectId != null && !projectId.equals(snapshot.getProjectId())) {
            throw new ResourceNotFoundException("Snapshot not found for project: id=" + snapshotId);
        }
        return TraceabilitySnapshotResponse.from(snapshot);
    }

    // ======================================================================
    // Deterministic Gap Detection Rules
    // ======================================================================

    private List<TraceabilityGap> detectRequirementGaps(
            Requirement req,
            List<Task> tasks,
            Set<RemoteCommitDto> commits,
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
                if (prs.isEmpty() && commits.isEmpty()) {
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
            if (analysis.getRiskScore() != null && (analysis.getRiskScore() >= 70.0 || (analysis.getRiskScore() <= 1.0 && analysis.getRiskScore() >= 0.70))) {
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
            return al.stream().anyMatch(a -> {
                if (a.getMergeReadiness() == null) return false;
                Object val = a.getMergeReadiness().get("decision");
                if (val == null) val = a.getMergeReadiness().get("status");
                String s = String.valueOf(val);
                return "BLOCKED".equalsIgnoreCase(s) || "NOT_READY".equalsIgnoreCase(s) || "BLOCKED_BY_MISSING_EVIDENCE".equalsIgnoreCase(s);
            });
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
            List<RemoteCommitDto> allCommits,
            Map<UUID, List<CodeLink>> codeLinksByTaskId,
            List<TraceabilitySpineChainDto> chains
    ) {
        List<TraceabilityGap> gaps = new ArrayList<>();

        // 1. ORPHAN_TASK: Task without parent requirement
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

        // 2. CODE_WITHOUT_PR: Commits linked to a task that have no matching PR in project repositories
        for (Task t : tasks) {
            List<CodeLink> clList = codeLinksByTaskId.getOrDefault(t.getId(), Collections.emptyList());
            for (CodeLink cl : clList) {
                if (cl.getLinkType() == CodeLinkType.COMMIT) {
                    String commitSha = cl.getExternalRef();
                    boolean hasAssociatedPr = allPrs.stream().anyMatch(pr ->
                            (pr.getHeadSha() != null && pr.getHeadSha().equalsIgnoreCase(commitSha)) ||
                            matchesText(pr.getTitle(), commitSha) ||
                            matchesText(pr.getBody(), commitSha) ||
                            matchesText(pr.getTitle(), t.getTaskKey()) ||
                            matchesText(pr.getBody(), t.getTaskKey()) ||
                            matchesText(pr.getHeadBranch(), t.getTaskKey())
                    );
                    if (!hasAssociatedPr) {
                        String shortSha = commitSha.length() >= 7 ? commitSha.substring(0, 7) : commitSha;
                        gaps.add(TraceabilityGap.builder()
                                .gapType(GapType.CODE_WITHOUT_PR)
                                .artifactId(t.getId().toString() + "#" + shortSha)
                                .artifactName(t.getTaskKey() + " (" + shortSha + ")")
                                .evidence("Task " + t.getTaskKey() + " has commit " + shortSha + " linked, but no Pull Request exists in project repositories")
                                .severity("MEDIUM")
                                .recommendation("Create a Pull Request for commit " + shortSha + " to enable review and CI verification.")
                                .build());
                    }
                }
            }
        }

        // 3. PR_WITHOUT_TASK: PRs not associated with any task in the project
        for (RemotePullRequestDto pr : allPrs) {
            boolean associatedWithTask = false;

            for (List<CodeLink> clList : codeLinksByTaskId.values()) {
                for (CodeLink cl : clList) {
                    if (cl.getLinkType() == CodeLinkType.PULL_REQUEST &&
                            cl.getExternalRef().equals(String.valueOf(pr.getPrNumber()))) {
                        associatedWithTask = true;
                        break;
                    }
                }
                if (associatedWithTask) break;
            }

            if (!associatedWithTask) {
                for (Task t : tasks) {
                    String tKey = t.getTaskKey().toLowerCase();
                    if (matchesText(pr.getTitle(), tKey) ||
                        matchesText(pr.getBody(), tKey) ||
                        matchesText(pr.getHeadBranch(), tKey)) {
                        associatedWithTask = true;
                        break;
                    }
                }
            }

            if (!associatedWithTask) {
                gaps.add(TraceabilityGap.builder()
                        .gapType(GapType.PR_WITHOUT_TASK)
                        .artifactId(String.valueOf(pr.getPrNumber()))
                        .artifactName("PR #" + pr.getPrNumber())
                        .evidence("PR #" + pr.getPrNumber() + " ('" + (pr.getTitle() != null ? pr.getTitle() : "Untitled") + "') is not associated with any task in this project")
                        .severity("LOW")
                        .recommendation("Reference a task key (e.g., " + (tasks.isEmpty() ? "TASK-###" : tasks.get(0).getTaskKey()) + ") in the PR title, body, or head branch.")
                        .build());
            }
        }

        // 4. PR_WITHOUT_REQUIREMENT: PRs that are associated with a task, but the task has no requirement
        Set<Integer> chainPrNumbers = chains.stream()
                .flatMap(c -> c.pullRequests().stream())
                .map(RemotePullRequestDto::getPrNumber)
                .collect(Collectors.toSet());

        for (RemotePullRequestDto pr : allPrs) {
            if (!chainPrNumbers.contains(pr.getPrNumber())) {
                boolean hasTask = false;
                for (Task t : tasks) {
                    if (t.getRequirementId() == null) {
                        String tKey = t.getTaskKey().toLowerCase();
                        if (matchesText(pr.getTitle(), tKey) || matchesText(pr.getBody(), tKey) || matchesText(pr.getHeadBranch(), tKey)) {
                            hasTask = true;
                            break;
                        }
                    }
                }
                if (hasTask) {
                    gaps.add(TraceabilityGap.builder()
                            .gapType(GapType.PR_WITHOUT_REQUIREMENT)
                            .artifactId(String.valueOf(pr.getPrNumber()))
                            .artifactName("PR #" + pr.getPrNumber())
                            .evidence("PR #" + pr.getPrNumber() + " is linked to an orphan task without a parent requirement")
                            .severity("MEDIUM")
                            .recommendation("Assign parent requirement to the task to integrate this PR into the delivery spine.")
                            .build());
                }
            }
        }

        return gaps;
    }

    // ======================================================================
    // Deterministic Project Health & Requirement Health Calculations
    // ======================================================================

    /**
     * Deterministic Project Health Scoring Formula:
     * -------------------------------------------------------------
     * Base Score = 100.0
     * Deductions:
     *   - HIGH / CRITICAL severity gap: -15.0 per occurrence
     *   - MEDIUM severity gap:          -8.0 per occurrence
     *   - LOW severity gap:             -3.0 per occurrence
     * Clamp: [0.0, 100.0]
     * <p>
     * Status Classification:
     *   - RED:   Any blocked requirement/task OR score < 50.0
     *   - AMBER: Score < 80.0 OR high risk requirement OR missing tests OR failing PRs OR unresolved findings
     *   - GREEN: Score >= 80.0 AND zero blockers AND zero failing PRs AND zero high risk gaps
     */
    private ProjectHealth computeProjectHealth(
            List<Requirement> requirements,
            List<Task> tasks,
            List<TraceabilitySpineChainDto> chains,
            List<TraceabilityGap> allGaps,
            List<RemotePullRequestDto> allPrs,
            List<RemoteAiAnalysisDto> allAnalyses,
            Map<String, List<RemoteTestRunDto>> testRunsBySha
    ) {
        int totalRequirements = requirements.size();
        int openTasks = (int) tasks.stream().filter(t -> t.getStatus() != TaskStatus.DONE).count();
        int blockedTasks = (int) tasks.stream().filter(t -> t.getStatus() == TaskStatus.BLOCKED).count();
        int activePrs = (int) allPrs.stream().filter(pr -> !Boolean.TRUE.equals(pr.getIsMerged())).count();

        // Calculate failing PRs based on actual CI test run failures and merge readiness
        int failingPrs = 0;
        for (RemotePullRequestDto pr : allPrs) {
            boolean failing = false;
            if (pr.getHeadSha() != null && testRunsBySha.containsKey(pr.getHeadSha())) {
                List<RemoteTestRunDto> runs = testRunsBySha.get(pr.getHeadSha());
                if (runs.stream().anyMatch(tr -> "FAILED".equalsIgnoreCase(tr.getStatus()) || (tr.getFailedTests() != null && tr.getFailedTests() > 0))) {
                    failing = true;
                }
            }
            if (!failing) {
                String prKey = pr.getRepositoryId() + "#" + pr.getPrNumber();
                failing = allAnalyses.stream()
                        .filter(a -> prKey.equals(a.getEntityId()))
                        .anyMatch(a -> {
                            if (a.getMergeReadiness() == null) return false;
                            Object val = a.getMergeReadiness().get("decision");
                            if (val == null) val = a.getMergeReadiness().get("status");
                            String s = String.valueOf(val);
                            return "BLOCKED".equalsIgnoreCase(s) || "NOT_READY".equalsIgnoreCase(s) || "BLOCKED_BY_MISSING_EVIDENCE".equalsIgnoreCase(s);
                        });
            }
            if (failing) {
                failingPrs++;
            }
        }

        // Calculate unresolved security & reliability findings from real AI analyses
        int unresolvedSecurityFindings = 0;
        int unresolvedReliabilityFindings = 0;
        boolean aiDataAvailable = !allAnalyses.isEmpty();

        for (RemoteAiAnalysisDto analysis : allAnalyses) {
            if (analysis.getSecurityAssessment() != null) {
                Map<String, Object> sec = analysis.getSecurityAssessment();
                Number crit = (Number) sec.get("critical_count");
                Number high = (Number) sec.get("high_count");
                Number med = (Number) sec.get("medium_count");
                Number low = (Number) sec.get("low_count");
                if (crit != null || high != null || med != null || low != null) {
                    unresolvedSecurityFindings += (crit != null ? crit.intValue() : 0)
                                                + (high != null ? high.intValue() : 0)
                                                + (med != null ? med.intValue() : 0)
                                                + (low != null ? low.intValue() : 0);
                } else if (sec.get("findings") instanceof List<?> flist) {
                    unresolvedSecurityFindings += flist.size();
                }
            }
            if (analysis.getReliabilityAssessment() != null) {
                Map<String, Object> rel = analysis.getReliabilityAssessment();
                Number crit = (Number) rel.get("critical_count");
                Number high = (Number) rel.get("high_count");
                Number med = (Number) rel.get("medium_count");
                Number low = (Number) rel.get("low_count");
                if (crit != null || high != null || med != null || low != null) {
                    unresolvedReliabilityFindings += (crit != null ? crit.intValue() : 0)
                                                   + (high != null ? high.intValue() : 0)
                                                   + (med != null ? med.intValue() : 0)
                                                   + (low != null ? low.intValue() : 0);
                } else if (rel.get("findings") instanceof List<?> flist) {
                    unresolvedReliabilityFindings += flist.size();
                }
            }
        }

        String evidenceStatus = "AVAILABLE";
        boolean scDown = sourceControlClient.isUnavailable();
        boolean testDown = testingServiceClient.isUnavailable();
        boolean aiDown = aiServiceClient.isUnavailable();

        if (scDown || testDown || aiDown) {
            evidenceStatus = (scDown && testDown && aiDown) ? "UNAVAILABLE" : "DEGRADED";
        } else if (!allPrs.isEmpty() && !aiDataAvailable) {
            evidenceStatus = "PARTIAL";
        }

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
                case "HIGH", "CRITICAL" -> score -= 15.0;
                case "MEDIUM" -> score -= 8.0;
                case "LOW" -> score -= 3.0;
            }
        }
        score = Math.max(0.0, Math.min(100.0, Math.round(score * 10.0) / 10.0));

        ProjectHealthStatus status;
        List<String> reasons = new ArrayList<>();

        if ("UNAVAILABLE".equals(evidenceStatus)) {
            status = ProjectHealthStatus.RED;
            reasons.add("Downstream evidence services are unavailable (circuit open); verification cannot be performed.");
        } else if (totalRequirements == 0 && tasks.isEmpty() && allPrs.isEmpty()) {
            status = "DEGRADED".equals(evidenceStatus) ? ProjectHealthStatus.AMBER : ProjectHealthStatus.GREEN;
            score = "DEGRADED".equals(evidenceStatus) ? 75.0 : 100.0;
            reasons.add("Project initialized with zero requirements and clean delivery slate.");
        } else if (blockedCount > 0 || blockedTasks > 0) {
            status = ProjectHealthStatus.RED;
            reasons.add(blockedCount + " requirement(s) or " + blockedTasks + " task(s) are currently blocked.");
        } else if (score < 50.0) {
            status = ProjectHealthStatus.RED;
            reasons.add("Traceability health score is critically low (" + score + "/100).");
        } else if ("DEGRADED".equals(evidenceStatus)) {
            status = ProjectHealthStatus.AMBER;
            reasons.add("Downstream evidence is degraded due to downstream service outage; provisional health status applied.");
        } else if (score < 80.0 || highRiskCount > 0 || reqsWithoutTests > 0 || failingPrs > 0 || unresolvedSecurityFindings > 0) {
            status = ProjectHealthStatus.AMBER;
            if (highRiskCount > 0) reasons.add(highRiskCount + " requirement(s) have high AI risk.");
            if (reqsWithoutTests > 0) reasons.add(reqsWithoutTests + " requirement(s) lack automated tests.");
            if (failingPrs > 0) reasons.add(failingPrs + " pull request(s) failing verification or blocked.");
            if (unresolvedSecurityFindings > 0) reasons.add(unresolvedSecurityFindings + " unresolved security finding(s).");
            if (unresolvedReliabilityFindings > 0) reasons.add(unresolvedReliabilityFindings + " unresolved reliability finding(s).");
            if (unresolvedRemediations > 0) reasons.add(unresolvedRemediations + " open AI remediation action(s).");
            if (reasons.isEmpty()) reasons.add("Traceability gaps require attention (score " + score + "/100).");
        } else {
            status = ProjectHealthStatus.GREEN;
            reasons.add("All requirements have continuous delivery traceability and verified test evidence.");
        }

        if (scDown) {
            reasons.add("Source control service is currently unavailable; downstream repository data degraded.");
        }
        if (testDown) {
            reasons.add("Testing service is currently unavailable; verification evidence degraded.");
        }
        if (aiDown) {
            reasons.add("AI service is currently unavailable; AI intelligence degraded.");
        }
        if ("PARTIAL".equals(evidenceStatus)) {
            reasons.add("Downstream AI intelligence partially available; metrics reflected from available records.");
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
                .failingPrs(failingPrs)
                .mergeReadinessBlockers((int) blockedCount)
                .unresolvedSecurityFindings(unresolvedSecurityFindings)
                .unresolvedReliabilityFindings(unresolvedReliabilityFindings)
                .activeRemediationActions((int) unresolvedRemediations)
                .evidenceStatus(evidenceStatus)
                .reasons(reasons)
                .build();
    }

    private RequirementHealth computeRequirementHealth(
            Requirement req,
            List<Task> tasks,
            Set<RemoteCommitDto> commits,
            Set<RemotePullRequestDto> prs,
            Set<RemoteTestRunDto> testRuns,
            List<RemoteRequirementTestTraceDto.RemoteTestCaseTraceSummary> testCases,
            Set<RemoteAiAnalysisDto> analyses,
            Set<RemoteEngineeringActionDto> actions
    ) {
        int tasksCount = tasks.size();
        long completedTasks = tasks.stream().filter(t -> t.getStatus() == TaskStatus.DONE).count();
        int prsCount = prs.size();
        int commitsCount = commits.size();
        long mergedPrs = prs.stream().filter(pr -> Boolean.TRUE.equals(pr.getIsMerged())).count();
        int testsCount = testCases.size();
        long passingTests = testCases.stream().filter(tc -> "PASSED".equalsIgnoreCase(tc.getLatestStatus())).count();
        boolean hasPassingTestRun = testRuns.stream().anyMatch(tr -> "COMPLETED".equalsIgnoreCase(tr.getStatus()) && (tr.getFailedTests() == null || tr.getFailedTests() == 0));
        boolean hasFailingTestRun = testRuns.stream().anyMatch(tr -> "FAILED".equalsIgnoreCase(tr.getStatus()) || (tr.getFailedTests() != null && tr.getFailedTests() > 0));
        boolean hasFailingTestCase = testCases.stream().anyMatch(tc -> "FAILED".equalsIgnoreCase(tc.getLatestStatus()));

        double riskScore = analyses.stream()
                .mapToDouble(a -> a.getRiskScore() != null ? a.getRiskScore() : 0.0)
                .max()
                .orElse(0.0);

        String mergeReadiness = prs.stream()
                .flatMap(pr -> analyses.stream().filter(a -> (pr.getRepositoryId() + "#" + pr.getPrNumber()).equals(a.getEntityId())))
                .map(a -> {
                    if (a.getMergeReadiness() == null) return "PENDING";
                    Object dec = a.getMergeReadiness().get("decision");
                    if (dec != null && !dec.toString().isBlank()) return String.valueOf(dec);
                    Object stat = a.getMergeReadiness().get("status");
                    if (stat != null && !stat.toString().isBlank()) return String.valueOf(stat);
                    return "PENDING";
                })
                .findFirst()
                .orElse("NONE");

        // Calculate completeness
        double completeness = 0.0;
        if (tasksCount > 0) completeness += 25.0;
        if (tasksCount > 0 && completedTasks == tasksCount) completeness += 25.0;
        if (testsCount > 0 || !testRuns.isEmpty()) completeness += 25.0;
        boolean isMergeReady = "READY".equalsIgnoreCase(mergeReadiness) || "READY_WITH_WARNINGS".equalsIgnoreCase(mergeReadiness);
        if ((passingTests > 0 || hasPassingTestRun) && (mergedPrs > 0 || isMergeReady)) {
            completeness += 25.0;
        }
        completeness = Math.min(100.0, completeness);

        boolean hasImplementation = prsCount > 0 || commitsCount > 0 || completedTasks > 0;
        boolean hasVerifiedTests = (passingTests > 0 || hasPassingTestRun) && !hasFailingTestCase && !hasFailingTestRun;
        boolean hasBlockedTask = tasks.stream().anyMatch(t -> t.getStatus() == TaskStatus.BLOCKED);
        boolean isHighRisk = (riskScore >= 70.0 || (riskScore <= 1.0 && riskScore >= 0.70));

        String implStatus = tasksCount == 0 ? "NOT_STARTED" : (completedTasks == tasksCount ? "IMPLEMENTED" : (hasImplementation ? "IMPLEMENTED" : "IN_PROGRESS"));
        String testStatus = testsCount == 0 && testRuns.isEmpty() ? "NO_TESTS" : (hasVerifiedTests ? "TESTS_PASSED" : (hasFailingTestCase || hasFailingTestRun ? "TESTS_FAILING" : "TESTS_PENDING"));
        String riskStatus = isHighRisk ? "HIGH" : ((riskScore >= 40.0 || (riskScore <= 1.0 && riskScore >= 0.40)) ? "MEDIUM" : (riskScore > 0 ? "LOW" : "NO_RISK"));
        String traceabilityStatus = (tasksCount > 0 && (prsCount > 0 || testsCount > 0)) ? "COMPLETE" : (tasksCount > 0 ? "PARTIAL" : "ORPHAN");

        RequirementHealthStatus status;
        if (tasksCount == 0) {
            status = RequirementHealthStatus.NOT_STARTED;
        } else if (hasBlockedTask || isHighRisk || hasFailingTestCase || hasFailingTestRun || "BLOCKED".equalsIgnoreCase(mergeReadiness) || "NOT_READY".equalsIgnoreCase(mergeReadiness)) {
            status = RequirementHealthStatus.AT_RISK;
        } else if (completedTasks == tasksCount && hasImplementation && hasVerifiedTests && (mergedPrs > 0 || isMergeReady)) {
            status = RequirementHealthStatus.COMPLETED;
        } else if (hasImplementation && hasVerifiedTests) {
            status = RequirementHealthStatus.VALIDATED;
        } else if (hasImplementation) {
            status = RequirementHealthStatus.IMPLEMENTED;
        } else {
            status = RequirementHealthStatus.IN_PROGRESS;
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
        if (organizationId == null && com.forgeai.project.infrastructure.security.TenantContext.getUserId() == null) {
            throw new IllegalArgumentException("Tenant context required: either Organization ID or User ID must be provided");
        }
    }
}
