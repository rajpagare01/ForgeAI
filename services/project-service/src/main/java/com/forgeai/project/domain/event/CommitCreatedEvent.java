package com.forgeai.project.domain.event;

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
public class CommitCreatedEvent implements DomainEvent {
    private UUID commitId;
    private UUID organizationId;
    private UUID repositoryId;
    private String sha;
    private String message;
    private String authorEmail;
    private String authorName;
    private Instant committedAt;

    @Builder.Default
    private Instant timestamp = Instant.now();

    @Override
    public String getEventType() {
        return "COMMIT_CREATED";
    }

    @Override
    public String getAggregateType() {
        return "COMMIT";
    }

    @Override
    public String getAggregateId() {
        return commitId != null ? commitId.toString() : sha;
    }
}
