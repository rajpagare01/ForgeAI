package com.forgeai.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record EmailChangeRequest(
        @NotBlank(message = "New email is required")
        @Email(message = "Invalid email format")
        String newEmail
) {}
