package com.forgeai.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateOrganizationRequest(
        @NotBlank(message = "Name is required")
        @Size(min = 3, max = 255, message = "Name must be between 3 and 255 characters")
        String name,

        @NotBlank(message = "Slug is required")
        @Size(min = 3, max = 100, message = "Slug must be between 3 and 100 characters")
        @Pattern(regexp = "^[a-z0-9-]+$", message = "Slug can only contain lowercase letters, numbers, and hyphens")
        String slug,

        @Size(max = 2000, message = "Description must not exceed 2000 characters")
        String description
) {
}
