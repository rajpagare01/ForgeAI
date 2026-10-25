package com.forgeai.identity.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitHubIdentity {
    private UUID id;
    private UUID userId;
    private Long githubUserId;
    private String githubLogin;
    private String name;
    private String email;
    private String avatarUrl;
    private String profileUrl;
    private String encryptedAccessToken;
    private String tokenType;
    private String scope;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastSyncedAt;
}
