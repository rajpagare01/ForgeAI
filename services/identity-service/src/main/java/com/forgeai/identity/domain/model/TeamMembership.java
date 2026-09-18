package com.forgeai.identity.domain.model;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class TeamMembership {
    private UUID id;
    private UUID userId;
    private UUID teamId;
    private UUID roleId;
}
