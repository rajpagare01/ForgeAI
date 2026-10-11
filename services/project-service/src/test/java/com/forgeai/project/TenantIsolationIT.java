package com.forgeai.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.project.application.command.CreateProjectCommand;
import com.forgeai.project.domain.model.Project;
import com.forgeai.project.domain.repository.ProjectRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class TenantIsolationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    @DisplayName("TenantIsolationIT: Assert querying project belonging to Org A using Org B context returns HTTP 404")
    void shouldEnforceStrictTenantIsolationAcrossOrganizations() throws Exception {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        // 1. Create Project in Organization A
        CreateProjectCommand commandA = new CreateProjectCommand("Tenant A Project", "TENANTA", "Description for Org A");

        String responseBody = mockMvc.perform(post("/api/v1/projects")
                        .header("X-Org-Id", orgA.toString())
                        .header("X-User-Id", userA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(commandA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("TENANTA"))
                .andReturn().getResponse().getContentAsString();

        Project createdProject = objectMapper.readValue(responseBody, Project.class);
        UUID projectId = createdProject.getId();

        // 2. Org A can access its own project
        mockMvc.perform(get("/api/v1/projects/" + projectId)
                        .header("X-Org-Id", orgA.toString())
                        .header("X-User-Id", userA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId.toString()));

        // 3. Org B attempts to access Org A's project -> MUST fail with 404 Not Found (Zero tenant leakage)
        mockMvc.perform(get("/api/v1/projects/" + projectId)
                        .header("X-Org-Id", orgB.toString())
                        .header("X-User-Id", userB.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        // 4. Listing projects for Org B should not contain Org A's project
        mockMvc.perform(get("/api/v1/projects")
                        .header("X-Org-Id", orgB.toString())
                        .header("X-User-Id", userB.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("TenantIsolationIT: Missing X-Org-Id header is rejected with HTTP 400 Bad Request")
    void shouldRejectRequestWithoutTenantHeader() throws Exception {
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing required X-Org-Id tenant context header"));
    }
}
