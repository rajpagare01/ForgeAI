package com.forgeai.identity.domain.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
public class Role {
    private UUID id;
    private UUID organizationId; // Nullable for system roles
    private String name;
    private String description;
    private RoleScope scope;
    private boolean systemDefined;
    private Set<Permission> permissions;
    private Instant createdAt;
    private Instant updatedAt;
}
