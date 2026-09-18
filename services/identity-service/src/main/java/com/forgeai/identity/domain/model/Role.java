package com.forgeai.identity.domain.model;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;
import java.util.Set;

@Getter
@Setter
public class Role {
    private UUID id;
    private String name;
    private Set<Permission> permissions;
}
