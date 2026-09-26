package com.forgeai.identity.domain.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class Team {
    private UUID id;
    private UUID organizationId;
    private String name;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;
}
