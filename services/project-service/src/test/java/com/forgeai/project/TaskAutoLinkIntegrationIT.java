package com.forgeai.project;

import com.forgeai.project.application.command.CreateProjectCommand;
import com.forgeai.project.application.command.CreateTaskCommand;
import com.forgeai.project.application.service.ProjectApplicationService;
import com.forgeai.project.application.service.TaskApplicationService;
import com.forgeai.project.domain.event.CommitCreatedEvent;
import com.forgeai.project.domain.event.EventEnvelope;
import com.forgeai.project.domain.event.PullRequestOpenedEvent;
import com.forgeai.project.domain.model.CodeLink;
import com.forgeai.project.domain.model.CodeLinkType;
import com.forgeai.project.domain.model.Project;
import com.forgeai.project.domain.model.Task;
import com.forgeai.project.domain.model.TaskPriority;
import com.forgeai.project.domain.model.UserDirectory;
import com.forgeai.project.domain.repository.CodeLinkRepository;
import com.forgeai.project.domain.repository.UserDirectoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class TaskAutoLinkIntegrationIT {

    @Autowired
    private ProjectApplicationService projectApplicationService;

    @Autowired
    private TaskApplicationService taskApplicationService;

    @Autowired
    private UserDirectoryRepository userDirectoryRepository;

    @Autowired
    private CodeLinkRepository codeLinkRepository;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Test
    @DisplayName("TaskAutoLinkIntegrationIT: CommitCreatedEvent with 'Fix password reset TASK-101' automatically links commit to TASK-101")
    void commitCreatedEventAutomaticallyLinksToMatchingTask() {
        UUID organizationId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();

        // 1. Seed active reporter user in user_directory
        userDirectoryRepository.save(UserDirectory.builder()
                .id(reporterId)
                .email("reporter@forgeai.com")
                .firstName("John")
                .lastName("Doe")
                .status("ACTIVE")
                .updatedAt(Instant.now())
                .build());

        // 2. Create Project
        Project project = projectApplicationService.createProject(organizationId, new CreateProjectCommand(
                "AutoLink Project",
                "AL1",
                "Testing auto link"
        ));

        // 3. Create Task with key "TASK-101"
        Task task = taskApplicationService.createTask(organizationId, project.getId(), reporterId, new CreateTaskCommand(
                null,
                "TASK-101",
                "Password reset bug",
                "Fix user password reset mechanism",
                TaskPriority.HIGH,
                null
        ));
        assertThat(task.getTaskKey()).isEqualTo("TASK-101");

        // 4. Publish CommitCreatedEvent matching TASK-101
        UUID repoId = UUID.randomUUID();
        String commitSha = "a1b2c3d4e5f67890123456789abcdef012345678";
        CommitCreatedEvent commitEvent = CommitCreatedEvent.builder()
                .commitId(UUID.randomUUID())
                .organizationId(organizationId)
                .repositoryId(repoId)
                .sha(commitSha)
                .message("Fix password reset TASK-101")
                .authorName("Dev Alice")
                .authorEmail("alice@forgeai.com")
                .committedAt(Instant.now())
                .timestamp(Instant.now())
                .build();

        applicationEventPublisher.publishEvent(EventEnvelope.from(commitEvent, "trace-autolink-1"));

        // 5. Assert CodeLink was automatically created in project-service
        List<CodeLink> links = codeLinkRepository.findAllByOrganizationIdAndTaskId(organizationId, task.getId());
        assertThat(links).hasSize(1);

        CodeLink link = links.get(0);
        assertThat(link.getTaskId()).isEqualTo(task.getId());
        assertThat(link.getLinkType()).isEqualTo(CodeLinkType.COMMIT);
        assertThat(link.getExternalRef()).isEqualTo(commitSha);
        assertThat(link.getRepositoryId()).isEqualTo(repoId);
        assertThat(link.getCreatedBy()).isEqualTo("GIT_COMMIT_AUTO_LINK");

        // 6. Verify PullRequestOpenedEvent also auto-links
        PullRequestOpenedEvent prEvent = PullRequestOpenedEvent.builder()
                .pullRequestId(UUID.randomUUID())
                .organizationId(organizationId)
                .repositoryId(repoId)
                .githubPrId(998877L)
                .number(105)
                .title("PR addressing TASK-101 issues")
                .body("Resolves TASK-101 thoroughly")
                .sourceBranch("fix/TASK-101")
                .targetBranch("main")
                .authorGithubId("alice")
                .timestamp(Instant.now())
                .build();

        applicationEventPublisher.publishEvent(EventEnvelope.from(prEvent, "trace-autolink-2"));

        List<CodeLink> updatedLinks = codeLinkRepository.findAllByOrganizationIdAndTaskId(organizationId, task.getId());
        assertThat(updatedLinks).hasSize(2);
        assertThat(updatedLinks).anyMatch(l ->
                l.getLinkType() == CodeLinkType.PULL_REQUEST &&
                "PR-#105".equals(l.getExternalRef())
        );
    }
}
