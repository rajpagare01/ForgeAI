package com.forgeai.identity.domain.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class TeamMembership {
    private UUID id;
    private UUID teamId;
    private UUID userId;
    private UUID roleId;
    private MembershipStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
