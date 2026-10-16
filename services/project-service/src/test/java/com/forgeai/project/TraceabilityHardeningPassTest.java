package com.forgeai.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.project.application.command.CreateProjectCommand;
import com.forgeai.project.application.command.CreateRequirementCommand;
import com.forgeai.project.application.command.CreateTaskCommand;
import com.forgeai.project.application.command.LinkCodeArtifactCommand;
import com.forgeai.project.domain.model.*;
import com.forgeai.project.domain.repository.TraceabilitySnapshotRepository;
import com.forgeai.project.infrastructure.client.AiServiceClient;
import com.forgeai.project.infrastructure.client.SourceControlClient;
import com.forgeai.project.infrastructure.client.TestingServiceClient;
import com.forgeai.project.infrastructure.client.dto.*;
import com.forgeai.project.presentation.dto.CreateProjectRepositoryLinkRequest;
import com.forgeai.project.presentation.dto.CreateSnapshotRequest;
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

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class TraceabilityHardeningPassTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TraceabilitySnapshotRepository snapshotRepository;

    @MockBean
    private SourceControlClient sourceControlClient;

    @MockBean
    private TestingServiceClient testingServiceClient;

    @MockBean
    private AiServiceClient aiServiceClient;

    @Test
    @DisplayName("Phase 1 & 2: CODE_WITHOUT_PR, PR_WITHOUT_TASK, and Real ProjectHealth Calculation")
    void testGapsAndHealthHardening() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();

        // 1. Mock Source Control repo
        RemoteRepositoryDto repoDto = RemoteRepositoryDto.builder()
                .id(repoId)
                .organizationId(orgId)
                .name("ForgeAI")
                .fullName("rajpagare01/ForgeAI")
                .build();
        Mockito.when(sourceControlClient.getRepository(eq(orgId), eq(repoId)))
                .thenReturn(Optional.of(repoDto));

        // PR #20 is unrelated to any task -> PR_WITHOUT_TASK
        RemotePullRequestDto unrelatedPr = RemotePullRequestDto.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .repositoryId(repoId)
                .prNumber(20)
                .title("chore: bump dependency versions")
                .body("Bump minor dependencies")
                .headBranch("chore/bump-deps")
                .headSha("c0ffee0123456789")
                .isMerged(false)
                .build();

        // PR #21 is failing CI tests -> failingPrs count
        RemotePullRequestDto failingPr = RemotePullRequestDto.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .repositoryId(repoId)
                .prNumber(21)
                .title("feat: [REQ-002] [TASK-202] critical payments engine")
                .body("Implements payments")
                .headBranch("feature/payments")
                .headSha("deadbeef01234567")
                .isMerged(false)
                .build();

        Mockito.when(sourceControlClient.listPullRequests(eq(orgId), eq(repoId)))
                .thenReturn(List.of(unrelatedPr, failingPr));
        Mockito.when(sourceControlClient.listCommits(eq(orgId), eq(repoId)))
                .thenReturn(Collections.emptyList());

        // Test run for PR #21 has failures
        RemoteTestRunDto failingTestRun = RemoteTestRunDto.builder()
                .id(UUID.randomUUID())
                .status("FAILED")
                .totalTests(10)
                .passedTests(8)
                .failedTests(2)
                .build();
        Mockito.when(testingServiceClient.listTestRuns(eq(orgId), eq(repoId), eq("deadbeef01234567")))
                .thenReturn(List.of(failingTestRun));

        // AI analysis for PR #21 has security & reliability findings
        Map<String, Object> secAssessment = Map.of(
                "critical_count", 1,
                "high_count", 2,
                "medium_count", 0,
                "low_count", 0
        );
        Map<String, Object> relAssessment = Map.of(
                "critical_count", 0,
                "high_count", 1,
                "medium_count", 1,
                "low_count", 0
        );
        RemoteAiAnalysisDto aiDto = RemoteAiAnalysisDto.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .entityId(repoId + "#21")
                .riskLevel("HIGH")
                .riskScore(0.85)
                .securityAssessment(secAssessment)
                .reliabilityAssessment(relAssessment)
                .mergeReadiness(Map.of("status", "BLOCKED"))
                .build();
        Mockito.when(aiServiceClient.getAnalysesForPullRequest(eq(orgId), eq(repoId), eq(21)))
                .thenReturn(List.of(aiDto));

        // Create Project
        String projResp = mockMvc.perform(post("/api/v1/projects")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProjectCommand("Hardening Test", "HARDEN", "Test"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Project project = objectMapper.readValue(projResp, Project.class);
        UUID projectId = project.getId();

        // Link repository
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/repositories")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProjectRepositoryLinkRequest(repoId, null))))
                .andExpect(status().isCreated());

        // Create Requirement REQ-002
        String reqResp = mockMvc.perform(post("/api/v1/projects/" + projectId + "/requirements")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRequirementCommand("REQ-002", "Payments API", "Core payments"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Requirement requirement = objectMapper.readValue(reqResp, Requirement.class);

        // Create Task TASK-201 (has commit link but NO PR -> CODE_WITHOUT_PR)
        String task1Resp = mockMvc.perform(post("/api/v1/projects/" + projectId + "/tasks")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTaskCommand(requirement.getId(), "TASK-201", "Unreviewed Commit Task", "Direct commit", TaskPriority.MEDIUM, null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Task task1 = objectMapper.readValue(task1Resp, Task.class);

        // Add commit code link to TASK-201
        mockMvc.perform(post("/api/v1/tasks/" + task1.getId() + "/links")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LinkCodeArtifactCommand(CodeLinkType.COMMIT, "1122334455667788", repoId, "USER"))))
                .andExpect(status().isCreated());

        // Create Task TASK-202 (associated with PR #21)
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/tasks")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTaskCommand(requirement.getId(), "TASK-202", "Reviewed Task", "Has PR", TaskPriority.HIGH, null))))
                .andExpect(status().isCreated());

        // Query gaps
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/traceability/gaps")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                // CODE_WITHOUT_PR must be present for TASK-201
                .andExpect(jsonPath("$[?(@.gapType == 'CODE_WITHOUT_PR')]").exists())
                // PR_WITHOUT_TASK must be present for PR #20
                .andExpect(jsonPath("$[?(@.gapType == 'PR_WITHOUT_TASK')]").exists());

        // Query health: verify failingPrs, unresolved findings, evidenceStatus
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/traceability/health")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failingPrs").value(1)) // PR #21 is failing
                .andExpect(jsonPath("$.unresolvedSecurityFindings").value(3)) // 1 crit + 2 high
                .andExpect(jsonPath("$.unresolvedReliabilityFindings").value(2)) // 1 high + 1 med
                .andExpect(jsonPath("$.evidenceStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.status").value("RED"));
    }

    @Test
    @DisplayName("Phase 3: Repository Binding Fail-Closed Authorization")
    void testRepositoryBindingAuthorization() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID otherOrgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID validRepoId = UUID.randomUUID();
        UUID unauthorizedRepoId = UUID.randomUUID();

        // Mock valid repo in orgId
        RemoteRepositoryDto validRepo = RemoteRepositoryDto.builder()
                .id(validRepoId)
                .organizationId(orgId)
                .fullName("valid-org/valid-repo")
                .name("valid-repo")
                .build();
        Mockito.when(sourceControlClient.getRepository(eq(orgId), eq(validRepoId)))
                .thenReturn(Optional.of(validRepo));

        // Mock unauthorized / nonexistent repo -> returns empty Optional
        Mockito.when(sourceControlClient.getRepository(eq(orgId), eq(unauthorizedRepoId)))
                .thenReturn(Optional.empty());

        // Create project
        String projResp = mockMvc.perform(post("/api/v1/projects")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProjectCommand("Auth Project", "AUTH", "Auth testing"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Project project = objectMapper.readValue(projResp, Project.class);

        // 1. Valid repository binds successfully and uses authoritative name
        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/repositories")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProjectRepositoryLinkRequest(validRepoId, "fake-client-name"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repositoryName").value("valid-org/valid-repo")); // Authoritative name, not fake name!

        // 2. Duplicate binding returns 409 Conflict
        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/repositories")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProjectRepositoryLinkRequest(validRepoId, null))))
                .andExpect(status().isConflict());

        // 3. Unauthorized / nonexistent repository returns 404 Not Found
        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/repositories")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProjectRepositoryLinkRequest(unauthorizedRepoId, null))))
                .andExpect(status().isNotFound());

        // 4. Access from other tenant organization returns 404 Not Found
        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/repositories")
                        .header("X-Org-Id", otherOrgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProjectRepositoryLinkRequest(validRepoId, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Phase 4: Granular Traceability Link Provenance")
    void testLinkProvenanceAccuracy() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();

        RemoteRepositoryDto repoDto = RemoteRepositoryDto.builder()
                .id(repoId)
                .organizationId(orgId)
                .fullName("rajpagare01/ForgeAI")
                .name("ForgeAI")
                .build();
        Mockito.when(sourceControlClient.getRepository(eq(orgId), eq(repoId)))
                .thenReturn(Optional.of(repoDto));

        // PR #30 matches task key in head branch
        RemotePullRequestDto branchPr = RemotePullRequestDto.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .repositoryId(repoId)
                .prNumber(30)
                .title("Feature Implementation")
                .headBranch("feature/TASK-301-login")
                .isMerged(false)
                .build();
        Mockito.when(sourceControlClient.listPullRequests(eq(orgId), eq(repoId)))
                .thenReturn(List.of(branchPr));

        // Commit matches in message
        RemoteCommitDto commit = RemoteCommitDto.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .repositoryId(repoId)
                .commitSha("aaabbbccc0001112")
                .message("fix(login): resolve TASK-301 validation edge cases")
                .build();
        Mockito.when(sourceControlClient.listCommits(eq(orgId), eq(repoId)))
                .thenReturn(List.of(commit));

        String projResp = mockMvc.perform(post("/api/v1/projects")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProjectCommand("Prov Project", "PROV", "Provenance"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Project project = objectMapper.readValue(projResp, Project.class);

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/repositories")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProjectRepositoryLinkRequest(repoId, null))))
                .andExpect(status().isCreated());

        String reqResp = mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/requirements")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRequirementCommand("REQ-301", "Login", "Login feature"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Requirement req = objectMapper.readValue(reqResp, Requirement.class);

        String taskResp = mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/tasks")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTaskCommand(req.getId(), "TASK-301", "Login task", "Desc", TaskPriority.HIGH, null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Task task = objectMapper.readValue(taskResp, Task.class);

        // Add explicit code link
        mockMvc.perform(post("/api/v1/tasks/" + task.getId() + "/links")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LinkCodeArtifactCommand(CodeLinkType.PULL_REQUEST, "30", repoId, "USER"))))
                .andExpect(status().isCreated());

        // Run sync
        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/traceability/sync")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk());

        // Verify full traceability links
        mockMvc.perform(get("/api/v1/projects/" + project.getId() + "/traceability")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                // Verify distinct origins exist and are NOT all COMMIT_MESSAGE
                .andExpect(jsonPath("$.links[?(@.linkOrigin == 'REQUIREMENT_TASK_RELATION')]").exists())
                .andExpect(jsonPath("$.links[?(@.linkOrigin == 'EXPLICIT_USER_LINK')]").exists())
                .andExpect(jsonPath("$.links[?(@.linkOrigin == 'COMMIT_MESSAGE')]").exists());
    }

    @Test
    @DisplayName("Phase 5: Snapshot Immutability Enforcement")
    void testSnapshotImmutability() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        String projResp = mockMvc.perform(post("/api/v1/projects")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProjectCommand("Snapshot Immutability", "SNAP", "Snap"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Project project = objectMapper.readValue(projResp, Project.class);

        // Create snapshot
        CreateSnapshotRequest req = new CreateSnapshotRequest("Governance Audit v1", null, null, null);
        String snapResp = mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/traceability/snapshots")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> snapMap = objectMapper.readValue(snapResp, Map.class);
        UUID snapId = UUID.fromString((String) snapMap.get("id"));

        // Fetch domain snapshot and verify save/mutation attempt throws UnsupportedOperationException
        TraceabilitySnapshot saved = snapshotRepository.findById(orgId, snapId).orElseThrow();
        saved.setSnapshotName("Tampered Name");

        assertThatThrownBy(() -> snapshotRepository.save(saved))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("immutable governance record");
    }
}
