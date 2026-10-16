package com.forgeai.project.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class RemoteCommitDto {
    private UUID id;
    private UUID organizationId;
    private UUID repositoryId;
    private String commitSha;
    private String message;
    private String authorName;
    private String authorEmail;
    private Instant authoredDate;
    private Instant committedDate;
    private String htmlUrl;
}
