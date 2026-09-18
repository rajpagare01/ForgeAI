package com.forgeai.identity.domain.model;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;
import java.time.Instant;

@Getter
@Setter
public class SecurityEvent {
    private UUID id;
    private UUID userId;
    private String eventType; // e.g. LOGIN_SUCCESS, LOGIN_FAILURE
    private Instant timestamp;
    private String ipAddress;
}
