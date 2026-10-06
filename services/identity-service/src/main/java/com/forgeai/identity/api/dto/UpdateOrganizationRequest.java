package com.forgeai.identity.api.dto;

import jakarta.validation.constraints.Size;

public record UpdateOrganizationRequest(
        @Size(min = 3, max = 255, message = "Name must be between 3 and 255 characters")
        String name,

        @Size(max = 2000, message = "Description must not exceed 2000 characters")
        String description
) {
}
