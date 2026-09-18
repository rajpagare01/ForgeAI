package com.forgeai.identity.domain.model;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;
import java.time.Instant;

@Getter
@Setter
public class Session {
    private UUID id;
    private UUID userId;
    private Instant createdAt;
    private Instant expiresAt;
}
