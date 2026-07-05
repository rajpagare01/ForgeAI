package com.forgeai.identity.presentation.request;

import com.forgeai.identity.presentation.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(
    @NotBlank(message = "Current password is required")
    String currentPassword,

    @NotBlank(message = "New password is required")
    @StrongPassword
    String newPassword
) {}
