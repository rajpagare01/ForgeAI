package com.forgeai.identity.presentation.request;

import com.forgeai.identity.presentation.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(
    @NotBlank(message = "Token is required")
    String token,

    @NotBlank(message = "New password is required")
    @StrongPassword
    String newPassword
) {}
