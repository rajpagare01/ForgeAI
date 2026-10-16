package com.forgeai.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.project.application.command.CreateProjectCommand;
import com.forgeai.project.application.command.CreateRequirementCommand;
import com.forgeai.project.application.command.CreateTaskCommand;
import com.forgeai.project.application.command.LinkCodeArtifactCommand;
import com.forgeai.project.domain.model.*;
import com.forgeai.project.infrastructure.client.AiServiceClient;
import com.forgeai.project.infrastructure.client.SourceControlClient;
import com.forgeai.project.infrastructure.client.TestingServiceClient;
import com.forgeai.project.infrastructure.client.dto.*;
import com.forgeai.project.presentation.dto.CreateProjectRepositoryLinkRequest;
import com.forgeai.project.presentation.dto.CreateSnapshotRequest;
import com.forgeai.project.presentation.dto.CreateTraceabilityLinkRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class UnifiedTraceabilityIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SourceControlClient sourceControlClient;

    @MockBean
    private TestingServiceClient testingServiceClient;

    @MockBean
    private AiServiceClient aiServiceClient;

    @Test
    @DisplayName("Unified Traceability: Multi-Repo Binding -> Delivery Spine -> Gap Detection -> Health -> Snapshot -> Tenant Isolation")
    void shouldExecuteUnifiedTraceabilityWorkflow() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID otherOrgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();

        // Setup mock responses for source-control, testing, and AI services
        RemoteRepositoryDto repoDto = RemoteRepositoryDto.builder()
                .id(repoId)
                .organizationId(orgId)
                .name("ForgeAI")
                .fullName("rajpagare01/ForgeAI")
                .htmlUrl("https://github.com/rajpagare01/ForgeAI")
                .defaultBranch("main")
                .build();
        Mockito.when(sourceControlClient.getRepository(eq(orgId), eq(repoId)))
                .thenReturn(Optional.of(repoDto));

        RemotePullRequestDto prDto = RemotePullRequestDto.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .repositoryId(repoId)
                .prNumber(10)
                .title("feat(auth): [REQ-001] [TASK-101] Implement Unified Traceability Engine")
                .body("Resolves REQ-001 and TASK-101 with end-to-end spine linking")
                .headBranch("feature/REQ-001-traceability")
                .headSha("8bd61fbaf16ab19fe350ff283ead2a45c8577b51")
                .isMerged(false)
                .build();
        Mockito.when(sourceControlClient.listPullRequests(eq(orgId), eq(repoId)))
                .thenReturn(List.of(prDto));

        RemoteCommitDto commitDto = RemoteCommitDto.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .repositoryId(repoId)
                .commitSha("8bd61fbaf16ab19fe350ff283ead2a45c8577b51")
                .message("feat(trace): REQ-001 TASK-101 unbroken delivery spine implementation")
                .build();
        Mockito.when(sourceControlClient.listCommits(eq(orgId), eq(repoId)))
                .thenReturn(List.of(commitDto));

        RemoteTestRunDto testRunDto = RemoteTestRunDto.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .repositoryId(repoId)
                .commitSha("8bd61fbaf16ab19fe350ff283ead2a45c8577b51")
                .branch("feature/REQ-001-traceability")
                .status("COMPLETED")
                .totalTests(12)
                .passedTests(12)
                .failedTests(0)
                .build();
        Mockito.when(testingServiceClient.listTestRuns(eq(orgId), eq(repoId), eq("8bd61fbaf16ab19fe350ff283ead2a45c8577b51")))
                .thenReturn(List.of(testRunDto));

        RemoteRequirementTestTraceDto testTraceDto = RemoteRequirementTestTraceDto.builder()
                .requirementKey("REQ-001")
                .totalTests(5)
                .passedTests(5)
                .failedTests(0)
                .tests(List.of(
                        RemoteRequirementTestTraceDto.RemoteTestCaseTraceSummary.builder()
                                .testCaseId(UUID.randomUUID())
                                .testName("shouldVerifyDeliverySpine")
                                .classname("UnifiedTraceabilityIT")
                                .latestStatus("PASSED")
                                .build()
                ))
                .build();
        Mockito.when(testingServiceClient.getTraceabilityForRequirement(eq(orgId), eq("REQ-001")))
                .thenReturn(Optional.of(testTraceDto));

        UUID analysisId = UUID.randomUUID();
        RemoteAiAnalysisDto analysisDto = RemoteAiAnalysisDto.builder()
                .id(analysisId)
                .organizationId(orgId)
                .entityType("PULL_REQUEST")
                .entityId(repoId + "#10")
                .summary("Low risk changes with verified AST analysis")
                .riskLevel("LOW")
                .riskScore(0.15)
                .confidence("HIGH")
                .mergeReadiness(Map.of("status", "READY", "readiness_score", 95.0))
                .build();
        Mockito.when(aiServiceClient.getAnalysesForPullRequest(eq(orgId), eq(repoId), eq(10)))
                .thenReturn(List.of(analysisDto));

        // 1. Create Project
        CreateProjectCommand projCmd = new CreateProjectCommand("ForgeAI Core Project", "FORGE", "Core architecture project");
        String projResp = mockMvc.perform(post("/api/v1/projects")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projCmd)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Project project = objectMapper.readValue(projResp, Project.class);
        UUID projectId = project.getId();

        // 2. Link Repository to Project
        CreateProjectRepositoryLinkRequest repoLinkReq = new CreateProjectRepositoryLinkRequest(repoId, "rajpagare01/ForgeAI");
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/repositories")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(repoLinkReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repositoryId").value(repoId.toString()))
                .andExpect(jsonPath("$.repositoryName").value("rajpagare01/ForgeAI"));

        // Verify Repository list endpoint
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/repositories")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].repositoryId").value(repoId.toString()));

        // 3. Create Requirement REQ-001
        CreateRequirementCommand reqCmd = new CreateRequirementCommand(
                "REQ-001",
                "Unified Engineering Traceability Spine",
                "Continuous traceability from requirement to code to test"
        );
        String reqResp = mockMvc.perform(post("/api/v1/projects/" + projectId + "/requirements")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqCmd)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Requirement requirement = objectMapper.readValue(reqResp, Requirement.class);
        UUID requirementId = requirement.getId();

        // 4. Create Task TASK-101 linked to REQ-001
        CreateTaskCommand taskCmd = new CreateTaskCommand(
                requirementId,
                "TASK-101",
                "Spine Aggregator Service",
                "Implement end-to-end spine aggregation",
                TaskPriority.HIGH,
                null
        );
        String taskResp = mockMvc.perform(post("/api/v1/projects/" + projectId + "/tasks")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(taskCmd)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Task task = objectMapper.readValue(taskResp, Task.class);
        UUID taskId = task.getId();

        // 5. Link Code Artifact to Task
        LinkCodeArtifactCommand codeLinkCmd = new LinkCodeArtifactCommand(
                CodeLinkType.PULL_REQUEST,
                "10",
                repoId,
                "USER"
        );
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/links")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(codeLinkCmd)))
                .andExpect(status().isCreated());

        // 6. Test Traceability Auto-Sync
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/traceability/sync")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.syncedLinks").isNumber());

        // 7. Get Full Project Traceability
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/traceability")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.health.status").isNotEmpty())
                .andExpect(jsonPath("$.chains[0].requirement.identifier").value("REQ-001"))
                .andExpect(jsonPath("$.chains[0].tasks[0].taskKey").value("TASK-101"))
                .andExpect(jsonPath("$.chains[0].pullRequests[0].prNumber").value(10))
                .andExpect(jsonPath("$.chains[0].testRuns[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.chains[0].aiAnalyses[0].riskLevel").value("LOW"));

        // 8. Verify Health endpoint
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/traceability/health")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthScore").isNumber())
                .andExpect(jsonPath("$.status").isNotEmpty())
                .andExpect(jsonPath("$.reasons").isArray());

        // 9. Verify Gaps endpoint
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/traceability/gaps")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 10. Verify Granular Requirement Traceability
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/traceability")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirement.id").value(requirementId.toString()))
                .andExpect(jsonPath("$.health.identifier").value("REQ-001"));

        // 11. Create and Retrieve Snapshot
        CreateSnapshotRequest snapshotReq = new CreateSnapshotRequest("Release v1.0 Governance Snapshot", 10, "8bd61fba", repoId);
        String snapResp = mockMvc.perform(post("/api/v1/projects/" + projectId + "/traceability/snapshots")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(snapshotReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.snapshotName").value("Release v1.0 Governance Snapshot"))
                .andReturn().getResponse().getContentAsString();

        // List Snapshots
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/traceability/snapshots")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].snapshotName").value("Release v1.0 Governance Snapshot"));

        // 12. Strict Tenant Isolation
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/traceability")
                        .header("X-Org-Id", otherOrgId.toString())
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/projects/" + projectId + "/repositories")
                        .header("X-Org-Id", otherOrgId.toString())
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
}
