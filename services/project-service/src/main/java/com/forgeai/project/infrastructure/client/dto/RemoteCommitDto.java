package com.forgeai.project.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
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

    @JsonAlias({"sha", "commitSha", "commit_sha"})
    private String commitSha;

    private String message;
    private String authorName;
    private String authorEmail;

    @JsonAlias({"authoredAt", "authored_at", "authoredDate"})
    private Instant authoredDate;

    @JsonAlias({"committedAt", "committed_at", "committedDate"})
    private Instant committedDate;

    @JsonAlias({"html_url", "htmlUrl"})
    private String htmlUrl;
}
