package com.forgeai.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.project.application.command.CreateProjectCommand;
import com.forgeai.project.application.command.CreateRequirementCommand;
import com.forgeai.project.application.command.CreateTaskCommand;
import com.forgeai.project.application.command.LinkCodeArtifactCommand;
import com.forgeai.project.domain.model.CodeLink;
import com.forgeai.project.domain.model.CodeLinkType;
import com.forgeai.project.domain.model.Project;
import com.forgeai.project.domain.model.Requirement;
import com.forgeai.project.domain.model.Task;
import com.forgeai.project.domain.model.TaskPriority;
import com.forgeai.project.infrastructure.persistence.entity.OutboxEventEntity;
import com.forgeai.project.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class TraceabilityFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SpringDataOutboxEventRepository outboxEventRepository;

    @Test
    @DisplayName("TraceabilityFlowIT: Requirement (REQ-###) -> Task (TASK-###) -> CodeLink (Branch / PR / Commit)")
    void shouldExecuteFullTraceabilitySpine() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();

        // 1. Create Project
        CreateProjectCommand projCmd = new CreateProjectCommand("ForgeAI Core", "FORGE", "Core architecture project");
        String projResp = mockMvc.perform(post("/api/v1/projects")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", reporterId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projCmd)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Project project = objectMapper.readValue(projResp, Project.class);
        UUID projectId = project.getId();

        // 2. Create Requirement: REQ-001
        CreateRequirementCommand reqCmd = new CreateRequirementCommand(
                "REQ-001",
                "Traceability Spine Architecture",
                "End-to-end auditability from requirement to Git commit"
        );
        String reqResp = mockMvc.perform(post("/api/v1/projects/" + projectId + "/requirements")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", reporterId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqCmd)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.identifier").value("REQ-001"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        Requirement requirement = objectMapper.readValue(reqResp, Requirement.class);
        UUID requirementId = requirement.getId();

        // 3. Create Task linked to Requirement: TASK-101
        CreateTaskCommand taskCmd = new CreateTaskCommand(
                requirementId,
                "TASK-101",
                "Implement Traceability Data Model",
                "Define tables and relationships",
                TaskPriority.HIGH,
                null
        );
        String taskResp = mockMvc.perform(post("/api/v1/projects/" + projectId + "/tasks")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", reporterId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(taskCmd)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taskKey").value("TASK-101"))
                .andExpect(jsonPath("$.requirementId").value(requirementId.toString()))
                .andReturn().getResponse().getContentAsString();
        Task task = objectMapper.readValue(taskResp, Task.class);
        UUID taskId = task.getId();

        // 4. Link Code Artifacts: Branch, Commit SHA, Pull Request
        String commitSha = "a1b2c3d4e5f67890123456789abcdef012345678";
        LinkCodeArtifactCommand commitLink = new LinkCodeArtifactCommand(
                CodeLinkType.COMMIT,
                commitSha,
                repoId,
                "GIT_WEBHOOK"
        );
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/links")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", reporterId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(commitLink)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.linkType").value("COMMIT"))
                .andExpect(jsonPath("$.externalRef").value(commitSha));

        // Link Pull Request
        LinkCodeArtifactCommand prLink = new LinkCodeArtifactCommand(
                CodeLinkType.PULL_REQUEST,
                "https://github.com/forgeai/forgeai/pull/42",
                repoId,
                "USER"
        );
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/links")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", reporterId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(prLink)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.linkType").value("PULL_REQUEST"));

        // 5. Verify Full Retrieval Path
        // Fetch task
        mockMvc.perform(get("/api/v1/tasks/" + taskId)
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", reporterId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId.toString()))
                .andExpect(jsonPath("$.requirementId").value(requirementId.toString()));

        // Fetch task links
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/links")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", reporterId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // 6. Verify Outbox Events emitted
        List<OutboxEventEntity> outboxEvents = outboxEventRepository.findAll();
        assertThat(outboxEvents).anyMatch(e -> "PROJECT_CREATED".equals(e.getEventType()));
        assertThat(outboxEvents).anyMatch(e -> "TASK_CREATED".equals(e.getEventType()));
        assertThat(outboxEvents).anyMatch(e -> "CODE_LINKED".equals(e.getEventType()));
    }
}
