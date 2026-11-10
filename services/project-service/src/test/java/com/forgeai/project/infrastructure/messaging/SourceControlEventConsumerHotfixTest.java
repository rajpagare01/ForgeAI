package com.forgeai.project.infrastructure.messaging;

import com.forgeai.project.domain.event.EventEnvelope;
import com.forgeai.project.domain.model.CodeLink;
import com.forgeai.project.domain.model.CodeLinkType;
import com.forgeai.project.domain.model.Task;
import com.forgeai.project.domain.model.TaskStatus;
import com.forgeai.project.domain.repository.CodeLinkRepository;
import com.forgeai.project.domain.repository.TaskRepository;
import com.forgeai.project.infrastructure.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SourceControlEventConsumerHotfixTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private CodeLinkRepository codeLinkRepository;

    @Mock
    private OutboxService outboxService;

    private SourceControlEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SourceControlEventConsumer(taskRepository, codeLinkRepository, outboxService);
    }

    @Test
    @DisplayName("handleApplicationEvent ignores Map payload when eventType is not PULL_REQUEST_MERGED")
    void handleApplicationEventIgnoresNonPullRequestMergedMapEnvelope() {
        EventEnvelope<Map<String, Object>> envelope = EventEnvelope.<Map<String, Object>>builder()
                .eventId(UUID.randomUUID())
                .eventType("INSTALLATION_UPDATED")
                .payload(Map.of("number", 1, "title", "TASK-101 update"))
                .build();

        consumer.handleApplicationEvent(envelope);

        verifyNoInteractions(codeLinkRepository, taskRepository);
    }

    @Test
    @DisplayName("onPullRequestMerged scopes CodeLink lookup by repositoryId, workspaceId, and organizationId")
    void onPullRequestMergedScopesCodeLinksByRepositoryAndTenant() {
        UUID targetOrgId = UUID.randomUUID();
        UUID targetWsId = UUID.randomUUID();
        UUID targetRepoId = UUID.randomUUID();
        UUID matchingTaskId = UUID.randomUUID();

        UUID otherRepoTaskId = UUID.randomUUID();
        UUID otherWsTaskId = UUID.randomUUID();
        UUID otherOrgTaskId = UUID.randomUUID();

        CodeLink matchingLink = CodeLink.builder()
                .id(UUID.randomUUID())
                .organizationId(targetOrgId)
                .workspaceId(targetWsId)
                .repositoryId(targetRepoId)
                .taskId(matchingTaskId)
                .linkType(CodeLinkType.PULL_REQUEST)
                .externalRef("PR-#1")
                .build();

        CodeLink differentRepoLink = CodeLink.builder()
                .id(UUID.randomUUID())
                .organizationId(targetOrgId)
                .workspaceId(targetWsId)
                .repositoryId(UUID.randomUUID())
                .taskId(otherRepoTaskId)
                .linkType(CodeLinkType.PULL_REQUEST)
                .externalRef("PR-#1")
                .build();

        CodeLink differentWorkspaceLink = CodeLink.builder()
                .id(UUID.randomUUID())
                .organizationId(targetOrgId)
                .workspaceId(UUID.randomUUID())
                .repositoryId(targetRepoId)
                .taskId(otherWsTaskId)
                .linkType(CodeLinkType.PULL_REQUEST)
                .externalRef("PR-#1")
                .build();

        CodeLink differentOrgLink = CodeLink.builder()
                .id(UUID.randomUUID())
                .organizationId(UUID.randomUUID())
                .workspaceId(targetWsId)
                .repositoryId(targetRepoId)
                .taskId(otherOrgTaskId)
                .linkType(CodeLinkType.PULL_REQUEST)
                .externalRef("PR-#1")
                .build();

        Task matchingTask = Task.builder()
                .id(matchingTaskId)
                .taskKey("TASK-201")
                .status(TaskStatus.IN_REVIEW)
                .build();

        when(codeLinkRepository.findAllByLinkTypeAndExternalRef(CodeLinkType.PULL_REQUEST, "PR-#1"))
                .thenReturn(List.of(matchingLink, differentRepoLink, differentWorkspaceLink, differentOrgLink));
        when(taskRepository.findById(matchingTaskId)).thenReturn(Optional.of(matchingTask));

        EventEnvelope<Map<String, Object>> envelope = EventEnvelope.<Map<String, Object>>builder()
                .eventId(UUID.randomUUID())
                .eventType("PULL_REQUEST_MERGED")
                .traceId("trace-pr-merge-1")
                .payload(Map.of(
                        "number", 1,
                        "organizationId", targetOrgId.toString(),
                        "workspaceId", targetWsId.toString(),
                        "repositoryId", targetRepoId.toString(),
                        "title", "Merge PR #1"
                ))
                .build();

        consumer.handleApplicationEvent(envelope);

        assertThat(matchingTask.getStatus()).isEqualTo(TaskStatus.DONE);
        verify(taskRepository, times(1)).findById(matchingTaskId);
        verify(taskRepository, times(1)).save(matchingTask);
        verify(taskRepository, never()).findById(otherRepoTaskId);
        verify(taskRepository, never()).findById(otherWsTaskId);
        verify(taskRepository, never()).findById(otherOrgTaskId);
    }
}

