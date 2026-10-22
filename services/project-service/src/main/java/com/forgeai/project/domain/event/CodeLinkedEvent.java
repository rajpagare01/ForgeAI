package com.forgeai.project.domain.event;

import com.forgeai.project.domain.model.CodeLinkType;
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
public class CodeLinkedEvent implements DomainEvent {

    private UUID codeLinkId;
    private UUID workspaceId;
    private UUID organizationId;
    private UUID taskId;
    private CodeLinkType linkType;
    private String externalRef;
    private UUID repositoryId;

    @Builder.Default
    private Instant timestamp = Instant.now();

    @Override
    public String getEventType() {
        return "CODE_LINKED";
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
