package com.forgeai.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangeUsernameRequest(
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 39, message = "Username must be between 3 and 39 characters")
        String username
) {}
