package com.forgeai.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddMemberRequest(
        @NotNull(message = "User ID is required")
        UUID userId,
        
        @NotBlank(message = "Role is required")
        String role
) {
}
