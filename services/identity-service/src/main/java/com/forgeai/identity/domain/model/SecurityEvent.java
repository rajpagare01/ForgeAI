package com.forgeai.identity.domain.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class SecurityEvent {
    private UUID id;
    private UUID userId;
    private UUID organizationId;
    private String eventType;
    private Instant createdAt;
    private String ipAddress;
    private String userAgent;
    private String details;
}
