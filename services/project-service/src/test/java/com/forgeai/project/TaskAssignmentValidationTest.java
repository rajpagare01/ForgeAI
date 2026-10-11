package com.forgeai.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.project.application.command.CreateProjectCommand;
import com.forgeai.project.application.command.CreateTaskCommand;
import com.forgeai.project.domain.model.Project;
import com.forgeai.project.domain.model.TaskPriority;
import com.forgeai.project.domain.model.UserDirectory;
import com.forgeai.project.domain.repository.UserDirectoryRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class TaskAssignmentValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserDirectoryRepository userDirectoryRepository;

    @Test
    @DisplayName("TaskAssignmentValidation: Reject assignment to non-existent user with HTTP 422")
    void shouldRejectAssignmentToNonExistentUser() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();

        // 1. Create Project
        CreateProjectCommand projCmd = new CreateProjectCommand("Assign Validation Project", "AVP1", "Desc");
        String projResp = mockMvc.perform(post("/api/v1/projects")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", reporterId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projCmd)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Project project = objectMapper.readValue(projResp, Project.class);

        // 2. Attempt task creation with random non-existent assignee
        UUID nonExistentAssignee = UUID.randomUUID();
        CreateTaskCommand taskCmd = new CreateTaskCommand(
                null,
                "TASK-1",
                "Implement feature",
                "Desc",
                TaskPriority.HIGH,
                nonExistentAssignee
        );

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/tasks")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", reporterId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(taskCmd)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("Assignee user does not exist in user directory: " + nonExistentAssignee));
    }

    @Test
    @DisplayName("TaskAssignmentValidation: Reject assignment to INACTIVE / SUSPENDED user with HTTP 422")
    void shouldRejectAssignmentToInactiveUser() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();

        // 1. Create Project
        CreateProjectCommand projCmd = new CreateProjectCommand("Inactive User Project", "IUP1", "Desc");
        String projResp = mockMvc.perform(post("/api/v1/projects")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", reporterId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projCmd)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Project project = objectMapper.readValue(projResp, Project.class);

        // 2. Insert INACTIVE user in user_directory
        UUID inactiveUserId = UUID.randomUUID();
        UserDirectory inactiveUser = UserDirectory.builder()
                .id(inactiveUserId)
                .email("inactive@forgeai.com")
                .firstName("Bob")
                .lastName("Suspended")
                .status("SUSPENDED")
                .updatedAt(Instant.now())
                .build();
        userDirectoryRepository.save(inactiveUser);

        // 3. Attempt task creation assigning to suspended user
        CreateTaskCommand taskCmd = new CreateTaskCommand(
                null,
                "TASK-2",
                "Suspended user task",
                "Desc",
                TaskPriority.MEDIUM,
                inactiveUserId
        );

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/tasks")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", reporterId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(taskCmd)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("Assignee user is not in ACTIVE status: " + inactiveUserId + " (status: SUSPENDED)"));
    }

    @Test
    @DisplayName("TaskAssignmentValidation: Allow assignment to ACTIVE user in user_directory")
    void shouldAllowAssignmentToActiveUser() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();

        // 1. Create Project
        CreateProjectCommand projCmd = new CreateProjectCommand("Active Assign Project", "AAP1", "Desc");
        String projResp = mockMvc.perform(post("/api/v1/projects")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", reporterId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projCmd)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Project project = objectMapper.readValue(projResp, Project.class);

        // 2. Insert ACTIVE user in user_directory
        UUID activeUserId = UUID.randomUUID();
        UserDirectory activeUser = UserDirectory.builder()
                .id(activeUserId)
                .email("active@forgeai.com")
                .firstName("Carol")
                .lastName("Engineer")
                .status("ACTIVE")
                .updatedAt(Instant.now())
                .build();
        userDirectoryRepository.save(activeUser);

        // 3. Attempt task creation assigning to active user -> succeeds
        CreateTaskCommand taskCmd = new CreateTaskCommand(
                null,
                "TASK-3",
                "Valid task assignment",
                "Desc",
                TaskPriority.MEDIUM,
                activeUserId
        );

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/tasks")
                        .header("X-Org-Id", orgId.toString())
                        .header("X-User-Id", reporterId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(taskCmd)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assigneeId").value(activeUserId.toString()))
                .andExpect(jsonPath("$.status").value("TODO"));
    }
}
