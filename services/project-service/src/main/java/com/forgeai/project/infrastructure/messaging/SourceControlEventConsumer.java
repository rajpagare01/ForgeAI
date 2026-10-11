package com.forgeai.project.infrastructure.messaging;

import com.forgeai.project.domain.event.BranchCreatedEvent;
import com.forgeai.project.domain.event.CodeLinkedEvent;
import com.forgeai.project.domain.event.CommitCreatedEvent;
import com.forgeai.project.domain.event.EventEnvelope;
import com.forgeai.project.domain.event.PullRequestOpenedEvent;
import com.forgeai.project.domain.model.CodeLink;
import com.forgeai.project.domain.model.CodeLinkType;
import com.forgeai.project.domain.model.Task;
import com.forgeai.project.domain.repository.CodeLinkRepository;
import com.forgeai.project.domain.repository.TaskRepository;
import com.forgeai.project.infrastructure.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class SourceControlEventConsumer {

    private static final Pattern TASK_KEY_PATTERN = Pattern.compile("TASK-\\d+", Pattern.CASE_INSENSITIVE);

    private final TaskRepository taskRepository;
    private final CodeLinkRepository codeLinkRepository;
    private final OutboxService outboxService;

    @EventListener
    @Transactional
    public void handleApplicationEvent(EventEnvelope<?> envelope) {
        if (envelope == null || envelope.getPayload() == null) {
            return;
        }

        Object payload = envelope.getPayload();
        if (payload instanceof CommitCreatedEvent commitEvent) {
            onCommitCreated(commitEvent);
        } else if (payload instanceof PullRequestOpenedEvent prEvent) {
            onPullRequestOpened(prEvent);
        } else if (payload instanceof BranchCreatedEvent branchEvent) {
            onBranchCreated(branchEvent);
        }
    }

    @EventListener
    @Transactional
    public void onCommitCreated(CommitCreatedEvent event) {
        if (event == null || event.getMessage() == null) {
            return;
        }

        Set<String> matchedTaskKeys = extractTaskKeys(event.getMessage());
        for (String taskKey : matchedTaskKeys) {
            Optional<Task> taskOpt = taskRepository.findByOrganizationIdAndTaskKey(event.getOrganizationId(), taskKey);
            if (taskOpt.isPresent()) {
                Task task = taskOpt.get();
                linkArtifact(task, CodeLinkType.COMMIT, event.getSha(), event.getRepositoryId(), "GIT_COMMIT_AUTO_LINK");
            } else {
                log.debug("No matching task found for key {} in org {}", taskKey, event.getOrganizationId());
            }
        }
    }

    @EventListener
    @Transactional
    public void onPullRequestOpened(PullRequestOpenedEvent event) {
        if (event == null) {
            return;
        }

        String combinedText = (event.getTitle() != null ? event.getTitle() : "") + " " +
                (event.getBody() != null ? event.getBody() : "") + " " +
                (event.getSourceBranch() != null ? event.getSourceBranch() : "");

        Set<String> matchedTaskKeys = extractTaskKeys(combinedText);
        String externalRef = "PR-#" + event.getNumber();
        for (String taskKey : matchedTaskKeys) {
            Optional<Task> taskOpt = taskRepository.findByOrganizationIdAndTaskKey(event.getOrganizationId(), taskKey);
            if (taskOpt.isPresent()) {
                Task task = taskOpt.get();
                linkArtifact(task, CodeLinkType.PULL_REQUEST, externalRef, event.getRepositoryId(), "GIT_PR_AUTO_LINK");
            }
        }
    }

    @EventListener
    @Transactional
    public void onBranchCreated(BranchCreatedEvent event) {
        if (event == null || event.getBranchName() == null) {
            return;
        }

        Set<String> matchedTaskKeys = extractTaskKeys(event.getBranchName());
        for (String taskKey : matchedTaskKeys) {
            Optional<Task> taskOpt = taskRepository.findByOrganizationIdAndTaskKey(event.getOrganizationId(), taskKey);
            if (taskOpt.isPresent()) {
                Task task = taskOpt.get();
                linkArtifact(task, CodeLinkType.BRANCH, event.getBranchName(), event.getRepositoryId(), "GIT_BRANCH_AUTO_LINK");
            }
        }
    }

    private void linkArtifact(Task task, CodeLinkType linkType, String externalRef, java.util.UUID repositoryId, String createdBy) {
        if (codeLinkRepository.existsByTaskIdAndLinkTypeAndExternalRef(task.getId(), linkType, externalRef)) {
            log.debug("Code link already exists: task={}, type={}, ref={}", task.getId(), linkType, externalRef);
            return;
        }

        Instant now = Instant.now();
        CodeLink codeLink = CodeLink.builder()
                .organizationId(task.getOrganizationId())
                .taskId(task.getId())
                .linkType(linkType)
                .externalRef(externalRef)
                .repositoryId(repositoryId)
                .createdBy(createdBy)
                .createdAt(now)
                .build();

        CodeLink saved = codeLinkRepository.save(codeLink);

        CodeLinkedEvent event = CodeLinkedEvent.builder()
                .codeLinkId(saved.getId())
                .organizationId(task.getOrganizationId())
                .taskId(task.getId())
                .linkType(saved.getLinkType())
                .externalRef(saved.getExternalRef())
                .repositoryId(saved.getRepositoryId())
                .timestamp(now)
                .build();

        outboxService.publish(event, "auto-link-" + saved.getId());
        log.info("Auto-linked Git artifact to task: taskId={}, key={}, linkType={}, ref={}",
                task.getId(), task.getTaskKey(), linkType, externalRef);
    }

    private Set<String> extractTaskKeys(String text) {
        Set<String> keys = new HashSet<>();
        if (text == null || text.isBlank()) {
            return keys;
        }
        Matcher matcher = TASK_KEY_PATTERN.matcher(text);
        while (matcher.find()) {
            keys.add(matcher.group().toUpperCase());
        }
        return keys;
    }
}
