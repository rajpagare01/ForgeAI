package com.forgeai.testing.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestSuite {
    private UUID id;
    private UUID organizationId;
    private UUID repositoryId;
    private String name;
    private String framework;
    private Instant createdAt;
    private Instant updatedAt;
}
