package com.forgeai.identity.presentation.request;

import com.forgeai.identity.presentation.validation.ValidDeviceId;
import com.forgeai.identity.presentation.validation.ValidUserAgent;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    String email,

    @NotBlank(message = "Password is required")
    String password,

    @ValidDeviceId
    String deviceId,

    @ValidUserAgent
    String userAgent
) {}
