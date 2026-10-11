package com.forgeai.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.project.application.command.CreateProjectCommand;
import com.forgeai.project.application.command.CreateTaskCommand;
import com.forgeai.project.application.command.UpdateTaskStatusCommand;
import com.forgeai.project.domain.model.Project;
import com.forgeai.project.domain.model.Task;
import com.forgeai.project.domain.model.TaskPriority;
import com.forgeai.project.domain.model.TaskStatus;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class TaskOptimisticLockingIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("TaskOptimisticLockingIT: Concurrent updates with stale version trigger HTTP 409 Conflict")
    void shouldThrowConflictWhenStaleVersionIsModified() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();

        // 1. Create Project
        CreateProjectCommand projCmd = new CreateProjectCommand("Optimistic Locking Proj", "OPT1", "Desc");
        String projResp = mockMvc.perform(post("/api/v1/projects")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", reporterId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projCmd)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Project project = objectMapper.readValue(projResp, Project.class);

        // 2. Create Task (initial version = 1)
        CreateTaskCommand taskCmd = new CreateTaskCommand(
                null,
                "TASK-CONC-1",
                "Concurrent Task",
                "Desc",
                TaskPriority.MEDIUM,
                null
        );

        String taskResp = mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/tasks")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", reporterId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(taskCmd)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Task task = objectMapper.readValue(taskResp, Task.class);
        UUID taskId = task.getId();
        Integer initialVersion = task.getVersion();
        assertThat(initialVersion).isEqualTo(1);

        // 3. User A updates status to IN_PROGRESS with version 1 -> succeeds, increments version to 2
        UpdateTaskStatusCommand userAUpdate = new UpdateTaskStatusCommand(TaskStatus.IN_PROGRESS, initialVersion);
        String userAResp = mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/status")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", reporterId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userAUpdate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.version").value(2))
                .andReturn().getResponse().getContentAsString();

        // 4. User B concurrently attempts to update with stale version 1 -> fails with HTTP 409 Conflict
        UpdateTaskStatusCommand userBUpdate = new UpdateTaskStatusCommand(TaskStatus.IN_REVIEW, initialVersion);
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/status")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", reporterId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userBUpdate)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Resource has been modified concurrently by another transaction. Please retry."));
    }
}
