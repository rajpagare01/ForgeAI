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
public class RemoteRepositoryDto {
    private UUID id;
    private UUID organizationId;
    private Long githubRepoId;
    private String name;
    private String fullName;
    private String defaultBranch;
    private String htmlUrl;
    private Boolean isPrivate;
    private Instant createdAt;
    private Instant updatedAt;
}
