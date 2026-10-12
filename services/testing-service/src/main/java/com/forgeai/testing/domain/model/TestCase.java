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
public class TestCase {
    private UUID id;
    private UUID organizationId;
    private UUID suiteId;
    private String name;
    private String classname;
    private String requirementKey;
    private Instant createdAt;
    private Instant updatedAt;
}
