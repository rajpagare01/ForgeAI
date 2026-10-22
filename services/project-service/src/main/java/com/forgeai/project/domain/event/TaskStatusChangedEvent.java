package com.forgeai.project.domain.event;

import com.forgeai.project.domain.model.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatusChangedEvent implements DomainEvent {

    private UUID taskId;
    private UUID projectId;
    private UUID workspaceId;
    private UUID organizationId;
    private String taskKey;
    private TaskStatus oldStatus;
    private TaskStatus newStatus;

    @Builder.Default
    private Instant timestamp = Instant.now();

    @Override
    public String getEventType() {
        return "TASK_STATUS_CHANGED";
    }

    @Override
    public String getAggregateType() {
        return "TASK";
    }

    @Override
    public String getAggregateId() {
        return taskId != null ? taskId.toString() : "";
    }
}
