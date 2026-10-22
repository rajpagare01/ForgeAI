package com.forgeai.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.project.application.command.CreateProjectCommand;
import com.forgeai.project.domain.model.Project;
import com.forgeai.project.domain.repository.ProjectRepository;
import com.forgeai.project.infrastructure.persistence.entity.WorkspaceJpaEntity;
import com.forgeai.project.infrastructure.persistence.repository.SpringDataWorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class WorkspaceProjectIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired(required = false)
    private SpringDataWorkspaceRepository workspaceRepository;

    private UUID ownerUserId;
    private UUID otherUserId;
    private UUID personalWorkspaceId;
    private UUID orgWorkspaceId;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        ownerUserId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();
        personalWorkspaceId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        orgWorkspaceId = orgId; // In ForgeAI, organization workspace ID matches org ID

        // Seed workspaces if repository is available
        if (workspaceRepository != null) {
            WorkspaceJpaEntity personalWs = WorkspaceJpaEntity.builder()
                    .id(personalWorkspaceId)
                    .type("PERSONAL")
                    .ownerUserId(ownerUserId)
                    .organizationId(null)
                    .name("Personal Workspace")
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            workspaceRepository.saveAndFlush(personalWs);

            WorkspaceJpaEntity orgWs = WorkspaceJpaEntity.builder()
                    .id(orgWorkspaceId)
                    .type("ORGANIZATION")
                    .ownerUserId(ownerUserId)
                    .organizationId(orgId)
                    .name("Engineering Org Workspace")
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            workspaceRepository.saveAndFlush(orgWs);
        }
    }

    @Test
    @DisplayName("UT-WS-08 & UT-WS-09: Personal project is created with workspace_id, null organization_id, and accessible by owner")
    void shouldCreateAndRetrievePersonalProjectForOwner() throws Exception {
        CreateProjectCommand command = new CreateProjectCommand(
                "Personal ML Tool",
                "MYML",
                "Individual machine learning workbench",
                personalWorkspaceId
        );

        String createRes = mockMvc.perform(post("/api/v1/projects")
                        .header("X-User-Id", ownerUserId.toString())
                        .header("X-ForgeAI-Internal", "attested")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("MYML"))
                .andExpect(jsonPath("$.workspaceId").value(personalWorkspaceId.toString()))
                .andExpect(jsonPath("$.organizationId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        Project created = objectMapper.readValue(createRes, Project.class);
        UUID projectId = created.getId();

        // Owner can access project
        mockMvc.perform(get("/api/v1/projects/" + projectId)
                        .header("X-User-Id", ownerUserId.toString())
                        .header("X-ForgeAI-Internal", "attested"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId.toString()))
                .andExpect(jsonPath("$.workspaceId").value(personalWorkspaceId.toString()));
    }

    @Test
    @DisplayName("UT-WS-10 & SEC-WS-03: Another user cannot access a personal project (403 Forbidden)")
    void shouldRejectAccessToPersonalProjectForOtherUser() throws Exception {
        CreateProjectCommand command = new CreateProjectCommand(
                "Secret Side Project",
                "SECRET",
                "Confidential notes",
                personalWorkspaceId
        );

        String createRes = mockMvc.perform(post("/api/v1/projects")
                        .header("X-User-Id", ownerUserId.toString())
                        .header("X-ForgeAI-Internal", "attested")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Project created = objectMapper.readValue(createRes, Project.class);
        UUID projectId = created.getId();

        // Another user tries to access -> MUST fail with 403 Forbidden
        mockMvc.perform(get("/api/v1/projects/" + projectId)
                        .header("X-User-Id", otherUserId.toString())
                        .header("X-ForgeAI-Internal", "attested"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("UT-WS-11: Organization project continues to work with workspace_id equals org_id")
    void shouldCreateAndAccessOrganizationProject() throws Exception {
        CreateProjectCommand command = new CreateProjectCommand(
                "Corporate Core Service",
                "CORP",
                "Organization backbone project",
                orgWorkspaceId
        );

        String createRes = mockMvc.perform(post("/api/v1/projects")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", ownerUserId.toString())
                        .header("X-Permissions", "PROJECT_CREATE,PROJECT_VIEW")
                        .header("X-ForgeAI-Internal", "attested")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("CORP"))
                .andExpect(jsonPath("$.organizationId").value(orgId.toString()))
                .andReturn().getResponse().getContentAsString();

        Project created = objectMapper.readValue(createRes, Project.class);
        UUID projectId = created.getId();

        // Accessible under organization context
        mockMvc.perform(get("/api/v1/projects/" + projectId)
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", ownerUserId.toString())
                        .header("X-ForgeAI-Internal", "attested"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId.toString()));
    }

    @Test
    @DisplayName("SEC-WS-04: Cross-tenant isolation prevents foreign org from accessing org project")
    void shouldPreventCrossOrgAccess() throws Exception {
        UUID foreignOrgId = UUID.randomUUID();

        CreateProjectCommand command = new CreateProjectCommand(
                "Org A Project",
                "ORGA",
                "Sensitive Org A data",
                orgWorkspaceId
        );

        String createRes = mockMvc.perform(post("/api/v1/projects")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", ownerUserId.toString())
                        .header("X-Permissions", "PROJECT_CREATE")
                        .header("X-ForgeAI-Internal", "attested")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Project created = objectMapper.readValue(createRes, Project.class);
        UUID projectId = created.getId();

        // Foreign org query must return 404 (Not Found)
        mockMvc.perform(get("/api/v1/projects/" + projectId)
                        .header("X-Org-Id", foreignOrgId.toString())
                        .header("X-User-Id", otherUserId.toString())
                        .header("X-ForgeAI-Internal", "attested"))
                .andExpect(status().isNotFound());
    }
}
