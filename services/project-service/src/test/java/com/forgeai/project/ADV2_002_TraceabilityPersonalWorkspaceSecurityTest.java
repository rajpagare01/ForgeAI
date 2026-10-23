package com.forgeai.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.project.application.command.CreateProjectCommand;
import com.forgeai.project.application.command.CreateRequirementCommand;
import com.forgeai.project.application.command.CreateTaskCommand;
import com.forgeai.project.domain.model.Project;
import com.forgeai.project.domain.model.Requirement;
import com.forgeai.project.domain.model.Task;
import com.forgeai.project.domain.model.TaskPriority;
import com.forgeai.project.domain.repository.ProjectRepository;
import com.forgeai.project.domain.repository.RequirementRepository;
import com.forgeai.project.domain.repository.TaskRepository;
import com.forgeai.project.domain.repository.TraceabilitySnapshotRepository;
import com.forgeai.project.infrastructure.client.AiServiceClient;
import com.forgeai.project.infrastructure.client.SourceControlClient;
import com.forgeai.project.infrastructure.client.TestingServiceClient;
import com.forgeai.project.infrastructure.persistence.entity.WorkspaceJpaEntity;
import com.forgeai.project.infrastructure.persistence.repository.SpringDataWorkspaceRepository;
import com.forgeai.project.presentation.dto.CreateSnapshotRequest;
import com.forgeai.project.presentation.dto.ProjectResponse;
import com.forgeai.project.presentation.dto.RequirementResponse;
import com.forgeai.project.presentation.dto.TaskResponse;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADV2_002_TraceabilityPersonalWorkspaceSecurityTest
 * <p>
 * Verifies that personal workspace projects:
 * 1. Allow full traceability aggregation, health scoring, gap detection, sync, and snapshot creation
 *    under legitimate owner authentication (X-User-Id with personal workspace, no X-Org-Id).
 * 2. Strictly reject cross-user access (403 Forbidden) when requested by a different user.
 * 3. Reject access when requested with an invalid/unrelated organization header.
 * 4. Reject access when neither organization nor user context is present (400 Bad Request).
 * 5. Correctly store snapshots with workspace_id and organization_id == null.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class ADV2_002_TraceabilityPersonalWorkspaceSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RequirementRepository requirementRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private SpringDataWorkspaceRepository springDataWorkspaceRepository;

    @Autowired
    private TraceabilitySnapshotRepository snapshotRepository;

    @MockBean
    private SourceControlClient sourceControlClient;

    @MockBean
    private TestingServiceClient testingServiceClient;

    @MockBean
    private AiServiceClient aiServiceClient;

    private UUID userA;
    private UUID userB;
    private UUID personalWorkspaceA;
    private UUID personalWorkspaceB;

    @BeforeEach
    void setUp() {
        userA = UUID.randomUUID();
        userB = UUID.randomUUID();
        personalWorkspaceA = UUID.randomUUID();
        personalWorkspaceB = UUID.randomUUID();

        // Seed workspaces
        springDataWorkspaceRepository.saveAndFlush(WorkspaceJpaEntity.builder()
                .id(personalWorkspaceA)
                .type("PERSONAL")
                .ownerUserId(userA)
                .organizationId(null)
                .name("Personal Workspace A")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        springDataWorkspaceRepository.saveAndFlush(WorkspaceJpaEntity.builder()
                .id(personalWorkspaceB)
                .type("PERSONAL")
                .ownerUserId(userB)
                .organizationId(null)
                .name("Personal Workspace B")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        // Default mock behaviors
        Mockito.when(sourceControlClient.listPullRequests(any(), any())).thenReturn(Collections.emptyList());
        Mockito.when(sourceControlClient.listPullRequests(any(), any(), any())).thenReturn(Collections.emptyList());
        Mockito.when(sourceControlClient.listCommits(any(), any())).thenReturn(Collections.emptyList());
        Mockito.when(sourceControlClient.listCommits(any(), any(), any())).thenReturn(Collections.emptyList());
        Mockito.when(testingServiceClient.listTestRuns(any(), any(), any())).thenReturn(Collections.emptyList());
        Mockito.when(testingServiceClient.listTestRuns(any(), any(), any(), any())).thenReturn(Collections.emptyList());
        Mockito.when(aiServiceClient.getAnalysesForPullRequest(any(), any(), Mockito.anyInt())).thenReturn(Collections.emptyList());
        Mockito.when(aiServiceClient.getAnalysesForPullRequest(any(), any(), any(), Mockito.anyInt())).thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("ADV2-002: Personal Project Traceability full lifecycle and strict cross-user isolation")
    void testPersonalProjectTraceabilitySecurity() throws Exception {
        // 1. Create personal project for User A
        CreateProjectCommand projCmd = new CreateProjectCommand(
                "Personal Edge AI",
                "PEAI",
                "Edge inference runner",
                personalWorkspaceA
        );

        String createProjRes = mockMvc.perform(post("/api/v1/projects")
                        .header("X-User-Id", userA.toString())
                        .header("X-Workspace-Id", personalWorkspaceA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projCmd)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        ProjectResponse projectA = objectMapper.readValue(createProjRes, ProjectResponse.class);
        UUID projectId = projectA.id();
        assertThat(projectA.organizationId()).isNull();
        assertThat(projectA.workspaceId()).isEqualTo(personalWorkspaceA);

        // 2. Create Requirement and Task in personal project
        CreateRequirementCommand reqCmd = new CreateRequirementCommand(
                "REQ-PEAI-01",
                "Model quantization support",
                "Support INT8 quantization"
        );

        String reqRes = mockMvc.perform(post("/api/v1/projects/" + projectId + "/requirements")
                        .header("X-User-Id", userA.toString())
                        .header("X-Workspace-Id", personalWorkspaceA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqCmd)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        RequirementResponse req = objectMapper.readValue(reqRes, RequirementResponse.class);

        CreateTaskCommand taskCmd = new CreateTaskCommand(
                req.id(),
                "TASK-PEAI-01",
                "Implement ONNX INT8 exporter",
                "Export script",
                TaskPriority.HIGH,
                null
        );

        String taskRes = mockMvc.perform(post("/api/v1/projects/" + projectId + "/tasks")
                        .header("X-User-Id", userA.toString())
                        .header("X-Workspace-Id", personalWorkspaceA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(taskCmd)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        TaskResponse task = objectMapper.readValue(taskRes, TaskResponse.class);

        // 3. User A queries Traceability, Health, Gaps (without X-Org-Id header) -> 200 OK
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/traceability")
                        .header("X-User-Id", userA.toString())
                        .header("X-Workspace-Id", personalWorkspaceA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.chains").isArray())
                .andExpect(jsonPath("$.health").exists());

        mockMvc.perform(get("/api/v1/projects/" + projectId + "/traceability/health")
                        .header("X-User-Id", userA.toString())
                        .header("X-Workspace-Id", personalWorkspaceA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequirements").value(1))
                .andExpect(jsonPath("$.openTasks").value(1));

        mockMvc.perform(get("/api/v1/projects/" + projectId + "/traceability/gaps")
                        .header("X-User-Id", userA.toString())
                        .header("X-Workspace-Id", personalWorkspaceA.toString()))
                .andExpect(status().isOk());

        // 4. User A syncs Traceability -> 200 OK
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/traceability/sync")
                        .header("X-User-Id", userA.toString())
                        .header("X-Workspace-Id", personalWorkspaceA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // 5. User A creates Traceability Snapshot -> 201 CREATED
        CreateSnapshotRequest snapshotReq = new CreateSnapshotRequest("Release v0.1-candidate", null, null, null);
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/traceability/snapshots")
                        .header("X-User-Id", userA.toString())
                        .header("X-Workspace-Id", personalWorkspaceA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(snapshotReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.snapshotName").value("Release v0.1-candidate"))
                .andExpect(jsonPath("$.projectId").value(projectId.toString()));

        // Verify snapshot in repository has workspaceId and null organizationId
        var snapshots = snapshotRepository.findByProjectId(projectId);
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).getWorkspaceId()).isEqualTo(personalWorkspaceA);
        assertThat(snapshots.get(0).getOrganizationId()).isNull();

        // 6. User A accesses Requirement and Task traceability directly -> 200 OK
        mockMvc.perform(get("/api/v1/requirements/" + req.id() + "/traceability")
                        .header("X-User-Id", userA.toString())
                        .header("X-Workspace-Id", personalWorkspaceA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirement.id").value(req.id().toString()));

        mockMvc.perform(get("/api/v1/tasks/" + task.id() + "/traceability")
                        .header("X-User-Id", userA.toString())
                        .header("X-Workspace-Id", personalWorkspaceA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(task.id().toString()));

        // ======================================================================
        // ATTACK VECTOR 1: User B tries to view User A's personal project traceability
        // ======================================================================
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/traceability")
                        .header("X-User-Id", userB.toString())
                        .header("X-Workspace-Id", personalWorkspaceB.toString()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/projects/" + projectId + "/traceability/health")
                        .header("X-User-Id", userB.toString())
                        .header("X-Workspace-Id", personalWorkspaceB.toString()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/projects/" + projectId + "/traceability/sync")
                        .header("X-User-Id", userB.toString())
                        .header("X-Workspace-Id", personalWorkspaceB.toString()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/requirements/" + req.id() + "/traceability")
                        .header("X-User-Id", userB.toString())
                        .header("X-Workspace-Id", personalWorkspaceB.toString()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/tasks/" + task.id() + "/traceability")
                        .header("X-User-Id", userB.toString())
                        .header("X-Workspace-Id", personalWorkspaceB.toString()))
                .andExpect(status().isForbidden());

        // ======================================================================
        // ATTACK VECTOR 2: Attacker specifies an arbitrary Organization ID
        // ======================================================================
        UUID arbitraryOrgId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/traceability")
                        .header("X-Org-Id", arbitraryOrgId.toString()))
                .andExpect(status().isNotFound());

        // ======================================================================
        // ATTACK VECTOR 3: No Tenant Headers provided (Fail Closed)
        // ======================================================================
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/traceability"))
                .andExpect(status().isBadRequest());
    }
}
