package com.forgeai.project.domain.event;

import com.forgeai.project.domain.model.TaskPriority;
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
public class TaskCreatedEvent implements DomainEvent {

    private UUID taskId;
    private UUID projectId;
    private UUID organizationId;
    private String taskKey;
    private String title;
    private TaskStatus status;
    private TaskPriority priority;
    private UUID assigneeId;
    private UUID reporterId;

    @Builder.Default
    private Instant timestamp = Instant.now();

    @Override
    public String getEventType() {
        return "TASK_CREATED";
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
