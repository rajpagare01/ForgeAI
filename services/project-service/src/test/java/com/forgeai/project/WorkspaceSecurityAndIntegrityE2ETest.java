package com.forgeai.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.project.application.command.CreateProjectCommand;
import com.forgeai.project.application.command.CreateRequirementCommand;
import com.forgeai.project.application.command.CreateTaskCommand;
import com.forgeai.project.application.command.LinkCodeArtifactCommand;
import com.forgeai.project.domain.model.*;
import com.forgeai.project.domain.repository.ProjectRepository;
import com.forgeai.project.domain.repository.RequirementRepository;
import com.forgeai.project.domain.repository.TaskRepository;
import com.forgeai.project.infrastructure.persistence.entity.ProjectJpaEntity;
import com.forgeai.project.infrastructure.persistence.entity.WorkspaceJpaEntity;
import com.forgeai.project.infrastructure.persistence.repository.SpringDataProjectRepository;
import com.forgeai.project.infrastructure.persistence.repository.SpringDataWorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class WorkspaceSecurityAndIntegrityE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private SpringDataProjectRepository springDataProjectRepository;

    @Autowired
    private SpringDataWorkspaceRepository springDataWorkspaceRepository;

    @Autowired
    private RequirementRepository requirementRepository;

    @Autowired
    private TaskRepository taskRepository;

    private UUID userA;
    private UUID userB;
    private UUID personalWorkspaceA;
    private UUID personalWorkspaceB;
    private UUID orgA;
    private UUID orgB;
    private UUID orgWorkspaceA;
    private UUID orgWorkspaceB;

    @BeforeEach
    void setUp() {
        userA = UUID.randomUUID();
        userB = UUID.randomUUID();
        personalWorkspaceA = UUID.randomUUID();
        personalWorkspaceB = UUID.randomUUID();
        orgA = UUID.randomUUID();
        orgB = UUID.randomUUID();
        orgWorkspaceA = orgA;
        orgWorkspaceB = orgB;

        // Seed Workspaces in local H2 / Test DB
        springDataWorkspaceRepository.saveAndFlush(WorkspaceJpaEntity.builder()
                .id(personalWorkspaceA)
                .type("PERSONAL")
                .ownerUserId(userA)
                .organizationId(null)
                .name("User A Studio")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        springDataWorkspaceRepository.saveAndFlush(WorkspaceJpaEntity.builder()
                .id(personalWorkspaceB)
                .type("PERSONAL")
                .ownerUserId(userB)
                .organizationId(null)
                .name("User B Studio")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        springDataWorkspaceRepository.saveAndFlush(WorkspaceJpaEntity.builder()
                .id(orgWorkspaceA)
                .type("ORGANIZATION")
                .ownerUserId(userA)
                .organizationId(orgA)
                .name("Organization A Core")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        springDataWorkspaceRepository.saveAndFlush(WorkspaceJpaEntity.builder()
                .id(orgWorkspaceB)
                .type("ORGANIZATION")
                .ownerUserId(userB)
                .organizationId(orgB)
                .name("Organization B Core")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
    }

    @Test
    @DisplayName("E2E-WS-01: Personal JWT -> Personal Workspace -> Project creation with null organization_id")
    void testPersonalWorkspaceProjectCreation() throws Exception {
        CreateProjectCommand command = new CreateProjectCommand(
                "Personal ML Engine",
                "PMLE",
                "Self-contained ML pipeline",
                personalWorkspaceA
        );

        String responseJson = mockMvc.perform(post("/api/v1/projects")
                        .header("X-User-Id", userA.toString())
                        .header("X-ForgeAI-Internal", "attested")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("PMLE"))
                .andExpect(jsonPath("$.workspaceId").value(personalWorkspaceA.toString()))
                .andExpect(jsonPath("$.organizationId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        Project created = objectMapper.readValue(responseJson, Project.class);
        assertThat(created.getWorkspaceId()).isEqualTo(personalWorkspaceA);
        assertThat(created.getOrganizationId()).isNull();
        WorkspaceJpaEntity ws = springDataWorkspaceRepository.findById(created.getWorkspaceId()).orElseThrow();
        assertThat(ws.getOwnerUserId()).isEqualTo(userA);
    }

    @Test
    @DisplayName("E2E-WS-05: User A -> User B Personal isolation (User B receives 403 Forbidden for User A resources)")
    void testUserAToUserBPersonalIsolation() throws Exception {
        // 1. Create User A Personal Project
        CreateProjectCommand command = new CreateProjectCommand(
                "User A Confidential",
                "CONF",
                "Strictly private project",
                personalWorkspaceA
        );

        String res = mockMvc.perform(post("/api/v1/projects")
                        .header("X-User-Id", userA.toString())
                        .header("X-ForgeAI-Internal", "attested")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Project projectA = objectMapper.readValue(res, Project.class);

        // 2. User A creates a requirement
        CreateRequirementCommand reqCmd = new CreateRequirementCommand("REQ-01", "Core Auth", "Must authenticate securely");
        mockMvc.perform(post("/api/v1/projects/" + projectA.getId() + "/requirements")
                        .header("X-User-Id", userA.toString())
                        .header("X-ForgeAI-Internal", "attested")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqCmd)))
                .andExpect(status().isCreated());

        // 3. User B attempts to access User A project -> 403 Forbidden
        mockMvc.perform(get("/api/v1/projects/" + projectA.getId())
                        .header("X-User-Id", userB.toString())
                        .header("X-ForgeAI-Internal", "attested"))
                .andExpect(status().isForbidden());

        // 4. User B attempts to list User A requirements -> 403 Forbidden
        mockMvc.perform(get("/api/v1/projects/" + projectA.getId() + "/requirements")
                        .header("X-User-Id", userB.toString())
                        .header("X-ForgeAI-Internal", "attested"))
                .andExpect(status().isForbidden());

        // 5. User B attempts to create requirement in User A project -> 403 Forbidden
        CreateRequirementCommand rogueReq = new CreateRequirementCommand("REQ-HACK", "Injected", "Unauthorized");
        mockMvc.perform(post("/api/v1/projects/" + projectA.getId() + "/requirements")
                        .header("X-User-Id", userB.toString())
                        .header("X-ForgeAI-Internal", "attested")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rogueReq)))
                .andExpect(status().isForbidden());

        // 6. User B attempts to list User A tasks -> 403 Forbidden
        mockMvc.perform(get("/api/v1/projects/" + projectA.getId() + "/tasks")
                        .header("X-User-Id", userB.toString())
                        .header("X-ForgeAI-Internal", "attested"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("E2E-WS-06: Organization A end-to-end flow retains organizationId equals workspaceId")
    void testOrganizationEndToEndFlow() throws Exception {
        CreateProjectCommand command = new CreateProjectCommand(
                "Org A Backend",
                "OABACK",
                "Org A primary backend",
                orgWorkspaceA
        );

        String res = mockMvc.perform(post("/api/v1/projects")
                        .header("X-Org-Id", orgA.toString())
                        .header("X-User-Id", userA.toString())
                        .header("X-Permissions", "PROJECT_CREATE,PROJECT_VIEW")
                        .header("X-ForgeAI-Internal", "attested")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizationId").value(orgA.toString()))
                .andExpect(jsonPath("$.workspaceId").value(orgA.toString()))
                .andReturn().getResponse().getContentAsString();

        Project project = objectMapper.readValue(res, Project.class);
        assertThat(project.getOrganizationId()).isEqualTo(orgA);
        assertThat(project.getWorkspaceId()).isEqualTo(orgA);

        // Sub-entities under Org A project retain Org A context
        CreateRequirementCommand reqCmd = new CreateRequirementCommand("REQ-ORG-1", "Org Spec", "Org spec requirement");
        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/requirements")
                        .header("X-Org-Id", orgA.toString())
                        .header("X-User-Id", userA.toString())
                        .header("X-ForgeAI-Internal", "attested")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqCmd)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizationId").value(orgA.toString()))
                .andExpect(jsonPath("$.workspaceId").value(orgA.toString()));
    }

    @Test
    @DisplayName("E2E-WS-07: Organization A -> Organization B isolation (Zero-leakage returns 404 Not Found)")
    void testOrganizationIsolation() throws Exception {
        CreateProjectCommand command = new CreateProjectCommand(
                "Org A Secret",
                "OASEC",
                "Org A confidential repo",
                orgWorkspaceA
        );

        String res = mockMvc.perform(post("/api/v1/projects")
                        .header("X-Org-Id", orgA.toString())
                        .header("X-User-Id", userA.toString())
                        .header("X-Permissions", "PROJECT_CREATE")
                        .header("X-ForgeAI-Internal", "attested")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Project projectA = objectMapper.readValue(res, Project.class);

        // Org B attempts to look up Org A project -> Zero leakage 404 Not Found
        mockMvc.perform(get("/api/v1/projects/" + projectA.getId())
                        .header("X-Org-Id", orgB.toString())
                        .header("X-User-Id", userB.toString())
                        .header("X-ForgeAI-Internal", "attested"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("E2E-WS-08: Workspace/Org consistency rejects mismatched workspace and organization combinations")
    void testWorkspaceOrgConsistency() throws Exception {
        // Attempt 1: Personal workspace with an Organization header -> REJECTED
        CreateProjectCommand command1 = new CreateProjectCommand(
                "Mismatched Project",
                "MIS1",
                "Personal ws with org header",
                personalWorkspaceA
        );

        mockMvc.perform(post("/api/v1/projects")
                        .header("X-Org-Id", orgA.toString())
                        .header("X-User-Id", userA.toString())
                        .header("X-ForgeAI-Internal", "attested")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command1)))
                .andExpect(status().isBadRequest());

        // Attempt 2: Organization workspace without Organization header -> REJECTED
        CreateProjectCommand command2 = new CreateProjectCommand(
                "Mismatched Project 2",
                "MIS2",
                "Org ws without org header",
                orgWorkspaceA
        );

        mockMvc.perform(post("/api/v1/projects")
                        .header("X-User-Id", userA.toString())
                        .header("X-ForgeAI-Internal", "attested")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command2)))
                .andExpect(status().isBadRequest());

        // Attempt 3: Organization workspace A with Organization B header -> REJECTED
        CreateProjectCommand command3 = new CreateProjectCommand(
                "Mismatched Project 3",
                "MIS3",
                "Org A ws with Org B header",
                orgWorkspaceA
        );

        mockMvc.perform(post("/api/v1/projects")
                        .header("X-Org-Id", orgB.toString())
                        .header("X-User-Id", userA.toString())
                        .header("X-ForgeAI-Internal", "attested")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command3)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DB-CHECK-01: Critical database CHECK constraint prevents invalid (NULL workspace_id) states")
    void testDatabaseCheckConstraintPreventsNullWorkspace() {
        ProjectJpaEntity invalidEntity = ProjectJpaEntity.builder()
                .id(UUID.randomUUID())
                .name("Illegal Null Workspace")
                .key("NULLWS")
                .workspaceId(null) // Illegal state
                .organizationId(null)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        assertThatThrownBy(() -> springDataProjectRepository.saveAndFlush(invalidEntity))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
