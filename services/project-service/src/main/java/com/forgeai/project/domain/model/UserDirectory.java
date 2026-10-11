package com.forgeai.project.domain.model;

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
public class UserDirectory {
    private UUID id;
    private String email;
    private String firstName;
    private String lastName;
    private String status;
    private Instant updatedAt;

    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}
