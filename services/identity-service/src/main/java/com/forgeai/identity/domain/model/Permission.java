package com.forgeai.identity.domain.model;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class Permission {
    private UUID id;
    private String name; // e.g. PROJECT_READ, TASK_CREATE
}
