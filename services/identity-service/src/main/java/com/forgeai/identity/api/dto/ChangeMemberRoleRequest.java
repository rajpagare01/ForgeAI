package com.forgeai.identity.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangeMemberRoleRequest(
        @NotBlank(message = "Role is required")
        String role
) {
}
