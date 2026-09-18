package com.forgeai.identity.domain.model;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;
import java.time.Instant;

@Getter
@Setter
public class RefreshToken {
    private UUID id;
    private UUID userId;
    private String token;
    private Instant expiresAt;
    private boolean revoked;
}
